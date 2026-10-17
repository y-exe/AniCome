package net.yexe.anicome

import org.junit.Assert.*
import org.junit.Test

class ServiceArtworkTest {
    @Test fun playArtworkMetadataIsParsedWithoutAcceptingOtherHostsOrSchemes() {
        assertEquals("https://play-lh.googleusercontent.com/icon?a=1&b=2", serviceArtworkUrl("""<meta property="og:image" content="https://play-lh.googleusercontent.com/icon?a=1&amp;b=2">"""))
        assertEquals("https://lh3.googleusercontent.com/icon", serviceArtworkUrl("""<meta content='https://lh3.googleusercontent.com/icon' property='og:image'>"""))
        assertNull(serviceArtworkUrl("""<meta property="og:image" content="http://play-lh.googleusercontent.com/icon">"""))
        assertNull(serviceArtworkUrl("""<meta property="og:image" content="https://play-lh.googleusercontent.com.evil.invalid/icon">"""))
        assertNull(serviceArtworkUrl("<html>No icon</html>"))
    }
}
