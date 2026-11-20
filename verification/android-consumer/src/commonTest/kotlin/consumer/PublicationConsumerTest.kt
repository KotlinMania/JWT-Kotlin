package consumer

import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PublicationConsumerTest {
    @Test
    fun publishedAsyncResultAndQuoteApi() {
        assertTrue("Future" in expandedResult().getOrThrow().toString())
        assertTrue(inferredBound().toString() == "Send")
    }

    @Test
    fun publishedThrowingApi() {
        assertTrue("Future" in expandedTokens().toString())
    }

    @Test
    fun publishedAndroidPropertyInputValidation() {
        assertNull(invalidProperty())
    }
}
