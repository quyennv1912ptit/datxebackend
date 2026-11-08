CREATE INDEX idx_rides_dashboard_created ON rides (created_at);
CREATE INDEX idx_rides_dashboard_completed ON rides (COALESCE(completed_at, created_at)) WHERE status = 'COMPLETED';
CREATE INDEX idx_rides_dashboard_customer_cancelled ON rides (cancelled_at)
    WHERE status = 'CANCELLED' AND cancelled_by_role = 'CUSTOMER';
