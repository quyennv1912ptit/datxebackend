"""Inventory and delete individually approved legacy driver images.

Uses psql with libpq environment variables and Cloudinary Admin/Upload APIs.
The default inventory mode only reads the database.
"""

import argparse
import base64
import hashlib
import json
import os
import re
import subprocess
import sys
import time
from pathlib import Path
from urllib.error import HTTPError, URLError
from urllib.parse import quote, urlencode, urlsplit
from urllib.request import HTTPRedirectHandler, Request, build_opener

MAX_ASSETS = 100
ASSET_ID = re.compile(r"[A-Za-z0-9_-]{16,128}")
PUBLIC_ID = re.compile(r"datxe/driver-documents/[1-9][0-9]*/[A-Za-z0-9_./-]+")
CLOUD = re.compile(r"[A-Za-z0-9_-]+")


def sql_string(value):
    return "'" + value.replace("'", "''") + "'"


def sql_identifier(value):
    return '"' + value.replace('"', '""') + '"'


def query(sql):
    result = subprocess.run(
        [os.getenv("PTITGO_CLEANUP_PSQL", "psql"), "-X", "-qAt", "-v", "ON_ERROR_STOP=1"],
        input="BEGIN ISOLATION LEVEL REPEATABLE READ READ ONLY;\nSET LOCAL standard_conforming_strings = on;\nSET LOCAL row_security = off;\n" + sql + ";\nCOMMIT;\n",
        capture_output=True,
        encoding="utf-8",
        check=False,
        timeout=120,
    )
    if result.returncode:
        raise RuntimeError("Read-only PostgreSQL query failed. Check database, schema and permissions.")
    return json.loads(result.stdout)


def database_identity():
    return query("""
        SELECT json_build_object(
            'database', current_database(), 'databaseOid', oid,
            'server', inet_server_addr(), 'port', inet_server_port(),
            'clusterId', (SELECT system_identifier::text FROM pg_control_system())
        ) FROM pg_database WHERE datname = current_database()
    """)


def has_legacy_table():
    return query("SELECT to_jsonb(to_regclass('public.driver_documents') IS NOT NULL)")


def has_legacy_license_column():
    return query("""
        SELECT to_jsonb(EXISTS (
            SELECT 1 FROM pg_attribute WHERE attrelid = to_regclass('public.drivers')
              AND attname = 'license_image_url' AND NOT attisdropped
        ))
    """)


def references(public_ids, before_migration):
    """Search retained relational and JSON data, including arrays and outbox records."""
    if not public_ids:
        return {}
    relations = query("""
        SELECT coalesce(json_agg(ARRAY[schemaname, tablename]), '[]'::json)
        FROM (SELECT schemaname, tablename FROM pg_tables
              UNION SELECT schemaname, matviewname FROM pg_matviews WHERE ispopulated) relations
        WHERE schemaname NOT IN ('pg_catalog', 'information_schema')
          AND schemaname NOT LIKE 'pg_%'
    """)
    sql_parts = []
    for schema, table in relations:
        if (schema, table) == ("public", "driver_documents"):
            continue
        data = "to_jsonb(row_data)"
        if before_migration and (schema, table) == ("public", "drivers"):
            data += " - 'license_image_url'"
        relation = ".".join(map(sql_identifier, (schema, table)))
        sql_parts.append(
            f"SELECT id, {sql_string(schema + '.' + table)} AS location FROM ids "
            f"WHERE EXISTS (SELECT 1 FROM {relation} row_data "
            f"WHERE position(ids.id in regexp_replace(({data})::text, '%2f', '/', 'gi')) > 0)"
        )
    if not sql_parts:
        raise RuntimeError("No reference tables found.")
    candidates = sql_string(json.dumps(public_ids))
    matches = query(f"""
        WITH ids AS (SELECT jsonb_array_elements_text({candidates}::jsonb) AS id),
        matches AS ({" UNION ALL ".join(sql_parts)})
        SELECT coalesce(json_object_agg(id, locations), '{{}}'::json)
        FROM (SELECT id, array_agg(location ORDER BY location) AS locations FROM matches GROUP BY id) grouped
    """)
    return matches


def inventory_stats():
    return query("""
        WITH legacy AS (
            SELECT DISTINCT driver_id AS id FROM public.driver_documents
            UNION SELECT id FROM public.drivers
              WHERE verification_status = 'APPROVED' AND NOT EXISTS (
                SELECT 1 FROM public.driver_verification_applications a
                JOIN public.driver_verification_submissions s
                  ON s.application_id = a.id AND s.revision = a.approved_submission_revision
                WHERE a.owner_uid = drivers.firebase_uid
                  AND s.snapshot_json::jsonb ->> 'legacyBackfill' IS DISTINCT FROM 'true'
              )
            UNION SELECT d.id FROM public.drivers d
              JOIN public.driver_verification_applications a ON a.owner_uid = d.firebase_uid
              JOIN public.driver_verification_submissions s ON s.application_id = a.id
              WHERE s.snapshot_json::jsonb ->> 'legacyBackfill' = 'true'
        ), native AS (
            SELECT d.id FROM public.drivers d JOIN public.driver_verification_applications a
              ON a.owner_uid = d.firebase_uid
            WHERE EXISTS (SELECT 1 FROM public.driver_verification_submissions s
                WHERE s.application_id = a.id AND s.snapshot_json::jsonb ->> 'legacyBackfill' IS DISTINCT FROM 'true')
              OR EXISTS (SELECT 1 FROM public.driver_verification_captures c WHERE c.application_id = a.id)
              OR EXISTS (SELECT 1 FROM public.driver_verification_audit au
                  WHERE au.application_id = a.id AND au.action = 'CREATE')
        ), active AS (
            SELECT DISTINCT driver_id AS id FROM public.rides
            WHERE status IN ('DRIVER_ASSIGNED', 'DRIVER_ARRIVING', 'DRIVER_ARRIVED',
                'VEHICLE_HANDOVER', 'IN_PROGRESS', 'ARRIVED_DESTINATION', 'VEHICLE_RETURNED')
        )
        SELECT json_build_object(
            'legacyOnlyDrivers', (SELECT count(*) FROM legacy LEFT JOIN native USING (id) WHERE native.id IS NULL),
            'nativeEkycDrivers', (SELECT count(*) FROM native),
            'mixedDrivers', (SELECT count(*) FROM legacy JOIN native USING (id)),
            'legacyDocumentRows', (SELECT count(*) FROM public.driver_documents),
            'legacyImagePublicIds', (SELECT count(DISTINCT cloudinary_public_id) FROM public.driver_documents WHERE resource_type = 'image'),
            'legacyDriversOnlineOrBusy', (SELECT count(*) FROM legacy JOIN public.drivers USING (id) WHERE drivers.status IN ('ONLINE', 'BUSY')),
            'legacyDriversWithActiveRides', (SELECT count(*) FROM legacy JOIN active USING (id)),
            'legacyActiveDriverIds', coalesce((SELECT json_agg(id ORDER BY id) FROM legacy JOIN active USING (id)), '[]'::json)
        )
    """)


def legacy_rows(after):
    return query(f"""
        SELECT coalesce(json_agg(json_build_object(
            'driverId', driver_id, 'publicId', cloudinary_public_id,
            'url', secure_url, 'resourceType', resource_type
        ) ORDER BY cloudinary_public_id), '[]'::json)
        FROM (SELECT DISTINCT driver_id, cloudinary_public_id, secure_url, resource_type
              FROM public.driver_documents
              WHERE cloudinary_public_id > {sql_string(after)}) docs
    """)


def in_scope(row, cloud):
    public_id = row["publicId"]
    if not PUBLIC_ID.fullmatch(public_id) or ".." in public_id.split("/"):
        return False
    if not public_id.startswith(f"datxe/driver-documents/{row['driverId']}/"):
        return False
    url = urlsplit(row["url"])
    prefix = re.escape(f"/{cloud}/image/upload/")
    path = re.escape(quote(public_id, safe="/"))
    return (row["resourceType"] == "image" and url.scheme == "https"
            and url.netloc == "res.cloudinary.com"
            and re.fullmatch(prefix + r"(?:v[0-9]+/)?" + path + r"\.[A-Za-z0-9]+", url.path) is not None)


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


class Cloudinary:
    def __init__(self, cloud):
        self.api_key = os.environ["PTITGO_CLEANUP_CLOUD_KEY"]
        self.secret = os.environ["PTITGO_CLEANUP_CLOUD_SECRET"]
        self.origin = "https://api.cloudinary.com/v1_1/" + cloud
        self.http = build_opener(NoRedirect())

    def send(self, request):
        try:
            with self.http.open(request, timeout=12) as response:
                return json.load(response)
        except HTTPError as error:
            if error.code == 404:
                return None
            raise RuntimeError(f"Cloudinary returned HTTP {error.code}.") from None
        except URLError:
            raise RuntimeError("Cloudinary connection failed.") from None

    def lookup(self, public_id):
        credentials = base64.b64encode(f"{self.api_key}:{self.secret}".encode()).decode()
        result = self.send(Request(
            self.origin + "/resources/image/upload/" + quote(public_id, safe=""),
            headers={"Authorization": "Basic " + credentials},
        ))
        if result and (result.get("public_id") != public_id or result.get("resource_type") != "image"
                       or result.get("type") != "upload" or not ASSET_ID.fullmatch(str(result.get("asset_id") or ""))
                       or not isinstance(result.get("secure_url"), str)):
            raise RuntimeError("Cloudinary asset identity did not match the requested image.")
        return (result["asset_id"], result["secure_url"]) if result else None

    def destroy(self, asset_id):
        fields = {"asset_id": asset_id, "invalidate": "true", "timestamp": str(int(time.time()))}
        signing_input = "&".join(f"{key}={fields[key]}" for key in sorted(fields))
        fields["signature"] = hashlib.sha256((signing_input + self.secret).encode()).hexdigest()
        fields["api_key"] = self.api_key
        result = self.send(Request(self.origin + "/destroy", data=urlencode(fields).encode()))
        status = result.get("result") if result else "not found"
        if status not in {"ok", "not found"}:
            raise RuntimeError("Cloudinary returned an unexpected delete result.")
        return "deleted" if status == "ok" else "already_missing"


def make_inventory(args, identity):
    if not has_legacy_table():
        raise RuntimeError("Collect the inventory from the pre-V42 schema or its isolated restore.")
    rows = legacy_rows(args.after_public_id)
    identifiers = sorted({row["publicId"] for row in rows})
    batch = identifiers[:args.limit]
    grouped = {public_id: [] for public_id in batch}
    for row in rows:
        if row["publicId"] in grouped:
            grouped[row["publicId"]].append(row)
    retained = references(batch, before_migration=True)
    service = Cloudinary(args.cloud) if args.resolve_cloud else None
    assets = []
    for public_id in batch:
        asset = {"publicId": public_id, "assetId": None, "references": retained.get(public_id, [])}
        if not all(in_scope(row, args.cloud) for row in grouped[public_id]):
            asset["status"] = "out_of_scope"
        elif asset["references"]:
            asset["status"] = "referenced"
        elif service:
            match = service.lookup(public_id)
            if match is None:
                asset["status"] = "already_missing"
            elif match[1] not in {row["url"] for row in grouped[public_id]}:
                asset["status"] = "asset_changed"
            else:
                asset["assetId"] = match[0]
                asset["deliveryUrlSha256"] = hashlib.sha256(match[1].encode()).hexdigest()
                asset["status"] = "resolved_candidate"
        else:
            asset["status"] = "unresolved_candidate"
        assets.append(asset)
    return {
        "version": 1, "mode": "dry_run", "environment": args.environment,
        "database": identity, "cloud": args.cloud, "stats": inventory_stats(), "assets": assets,
        "inspectedAssetCount": len(assets),
        "deletionCandidateCount": sum(a["status"] in {"resolved_candidate", "unresolved_candidate"} for a in assets),
        "nextAfterPublicId": batch[-1] if len(identifiers) > len(batch) else None,
    }


def execute_manifest(args, identity):
    if not args.manifest or not args.approved_sha256:
        raise RuntimeError("Execution requires a reviewed manifest and its approved SHA-256.")
    if has_legacy_table() or has_legacy_license_column():
        raise RuntimeError("Execution requires the post-V42 database after legacy writers are retired.")
    manifest_bytes = args.manifest.read_bytes()
    if hashlib.sha256(manifest_bytes).hexdigest() != args.approved_sha256.lower():
        raise RuntimeError("Manifest does not match the approved SHA-256.")
    manifest = json.loads(manifest_bytes)
    if (manifest.get("version") != 1 or manifest.get("mode") != "dry_run"
            or manifest.get("environment") != args.environment or manifest.get("cloud") != args.cloud
            or manifest.get("database") != identity):
        raise RuntimeError("Manifest belongs to a different environment.")
    candidates = [a for a in manifest["assets"] if a["status"] == "resolved_candidate"]
    public_ids = [a["publicId"] for a in candidates]
    if not 0 < len(public_ids) <= args.limit or len(set(public_ids)) != len(public_ids):
        raise RuntimeError("Manifest is empty, contains duplicate candidates or exceeds the explicit limit.")
    if any(not PUBLIC_ID.fullmatch(a["publicId"]) or ".." in a["publicId"].split("/")
           or not ASSET_ID.fullmatch(a.get("assetId") or "")
           or not re.fullmatch(r"[0-9a-f]{64}", a.get("deliveryUrlSha256") or "") for a in candidates):
        raise RuntimeError("Manifest contains an out-of-scope asset identity.")

    retained = references(public_ids + [item["assetId"] for item in candidates], before_migration=False)
    service = Cloudinary(args.cloud)
    results = []
    for item in candidates:
        public_id = item["publicId"]
        result = {"publicId": public_id, "assetId": item["assetId"]}
        try:
            locations = sorted(set(retained.get(public_id, []) + retained.get(item["assetId"], [])))
            if locations:
                result.update(status="referenced", references=locations)
            else:
                current = service.lookup(public_id)
                if current is None:
                    result["status"] = "already_missing"
                elif current[0] != item["assetId"] or hashlib.sha256(current[1].encode()).hexdigest() != item["deliveryUrlSha256"]:
                    result["status"] = "asset_changed"
                else:
                    result["status"] = service.destroy(current[0])
        except (RuntimeError, ValueError) as error:
            result.update(status="failed", error=str(error))
        results.append(result)
    return {"mode": "execution", "environment": args.environment, "cloud": args.cloud, "results": results,
            "counts": {status: sum(r["status"] == status for r in results)
                       for status in ("deleted", "already_missing", "referenced", "asset_changed", "failed")}}


def summary(report):
    if report["mode"] == "execution":
        return {"mode": "execution", "counts": report["counts"]}
    return {
        "mode": "dry_run",
        "stats": {key: value for key, value in report["stats"].items() if key != "legacyActiveDriverIds"},
        "inspectedAssetCount": report["inspectedAssetCount"],
        "deletionCandidateCount": report["deletionCandidateCount"],
        "hasMore": report["nextAfterPublicId"] is not None,
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--environment", required=True)
    parser.add_argument("--database", required=True)
    parser.add_argument("--cloud", required=True)
    parser.add_argument("--limit", type=int, default=25)
    parser.add_argument("--after-public-id", default="")
    parser.add_argument("--resolve-cloud", action="store_true", help="Read immutable Cloudinary IDs during dry-run.")
    parser.add_argument("--manifest", type=Path)
    parser.add_argument("--approved-sha256")
    parser.add_argument("--execute", action="store_true")
    parser.add_argument("--output", type=Path, help="Write a new access-controlled local report.")
    args = parser.parse_args()
    if args.resolve_cloud and not args.output:
        parser.error("Use --output in a protected directory to save the resolved manifest.")
    if not 1 <= args.limit <= MAX_ASSETS or not CLOUD.fullmatch(args.cloud):
        parser.error(f"Cloud name must be valid and limit must be between 1 and {MAX_ASSETS}.")
    if not all(os.getenv(key) == value for key, value in {
        "PTITGO_CLEANUP_ENV": args.environment,
        "PGDATABASE": args.database,
        "PTITGO_CLEANUP_CLOUD": args.cloud,
    }.items()):
        parser.error("Explicit environment, database and cloud must match the cleanup environment variables.")
    try:
        identity = database_identity()
        if identity["database"] != args.database:
            raise RuntimeError("Connected to an unexpected database.")
        if args.output:
            descriptor = os.open(args.output, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
            with os.fdopen(descriptor, "w", encoding="utf-8") as output:
                report = execute_manifest(args, identity) if args.execute else make_inventory(args, identity)
                json.dump(report, output, ensure_ascii=False, indent=2)
                output.write("\n")
        else:
            report = execute_manifest(args, identity) if args.execute else make_inventory(args, identity)
        print(json.dumps(summary(report), ensure_ascii=False, indent=2))
    except RuntimeError as error:
        print(f"Cleanup stopped: {error}", file=sys.stderr)
        return 1
    except (OSError, ValueError, KeyError, subprocess.TimeoutExpired):
        print("Cleanup stopped because a required file, setting, or provider response could not be read.", file=sys.stderr)
        return 1
    return int(args.execute and any(report["counts"][key] for key in ("failed", "asset_changed", "referenced")))


if __name__ == "__main__":
    sys.exit(main())
