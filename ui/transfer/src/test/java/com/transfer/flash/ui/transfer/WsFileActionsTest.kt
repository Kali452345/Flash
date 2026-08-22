package com.transfer.flash.ui.transfer

import com.transfer.flash.core.transfer.model.WsTransferDirection
import com.transfer.flash.core.transfer.model.WsTransferItem
import com.transfer.flash.core.transfer.model.WsTransferStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class WsFileActionsTest {

    @Test
    fun `resolveMimeType returns expected types for common extensions`() {
        assertEquals("application/pdf", WsFileActions.resolveMimeType("document.pdf"))
        assertEquals("image/jpeg", WsFileActions.resolveMimeType("photo.jpg"))
        assertEquals("image/png", WsFileActions.resolveMimeType("image.PNG"))
        assertEquals("video/mp4", WsFileActions.resolveMimeType("video.mp4"))
        assertEquals("audio/mpeg", WsFileActions.resolveMimeType("song.mp3"))
        assertEquals("application/zip", WsFileActions.resolveMimeType("archive.zip"))
        assertEquals("*/*", WsFileActions.resolveMimeType("unknownextensionfile.xyz123"))
        assertEquals("*/*", WsFileActions.resolveMimeType("noextension"))
    }

    @Test
    fun `WsTransferItem retains filePath when completed`() {
        val item = WsTransferItem(
            id = "test-1",
            peerName = "Pixel 9",
            fileName = "report.pdf",
            direction = WsTransferDirection.RECEIVING,
            bytesDone = 1024,
            bytesTotal = 1024,
            status = WsTransferStatus.COMPLETED,
            detail = "Saved to ws-received/report.pdf",
            filePath = "/data/user/0/com.transfer.flash/files/ws-received/report.pdf",
        )
        assertNotNull(item.filePath)
        assertEquals("/data/user/0/com.transfer.flash/files/ws-received/report.pdf", item.filePath)
        assertEquals(WsTransferStatus.COMPLETED, item.status)
    }
}
