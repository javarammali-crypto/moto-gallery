package com.javaram.gallery

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Face-derived content (People row, person search, person memories) is shown only when
 * recognition is explicitly on. Off, still-loading and unavailable all hide it.
 */
class FacesVisibleTest {

    @Test
    fun onlyExplicitlyOnShowsFaces() {
        assertTrue(facesVisible(true))
    }

    @Test
    fun offHidesFaces() {
        assertFalse(facesVisible(false))
    }

    @Test
    fun loadingHidesFaces() {
        assertFalse(facesVisible(null))
    }
}
