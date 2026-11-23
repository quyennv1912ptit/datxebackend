package com.nhom15.datxebackend.uploads

import com.cloudinary.Cloudinary
import com.nhom15.datxebackend.common.exception.BadRequestException
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verifyNoInteractions

class CloudinaryDeleteGuardTests {
    @Test
    fun `delete after commit rejects an asset outside the supplied owner folder`() {
        val cloudinary = mock(Cloudinary::class.java)
        val service = CloudinaryAssetService(cloudinary)

        assertThrows(BadRequestException::class.java) {
            service.deleteAfterCommit("app_dat_xe/customer/9/avatar", "app_dat_xe/customer/7")
        }

        verifyNoInteractions(cloudinary)
    }

    @Test
    fun `delete after commit rejects traversal even without an expected folder`() {
        val cloudinary = mock(Cloudinary::class.java)
        val service = CloudinaryAssetService(cloudinary)

        assertThrows(BadRequestException::class.java) {
            service.deleteAfterCommit("app_dat_xe/customer/7/../admin/1/avatar")
        }

        verifyNoInteractions(cloudinary)
    }
}
