package com.screenkit.screenrecorder.ui.overlay

import org.junit.Assert.*
import org.junit.Test

class BubblePlacementTest {
    private fun place(x: Int, y: Int = 240, expanded: Boolean = true, width: Int = 360, height: Int = 800, menuHeight: Int = 120) =
        BubblePlacement.layout(x, y, width, height, 42, 245, menuHeight, 4, 4, expanded)

    @Test fun rightEdgeSurvivesRepeatedExpansionAndCollapse() {
        repeat(100) {
            val open = place(318)
            assertTrue(open.menuBeforeBubble)
            assertEquals(61, open.x)
            assertEquals(318, place(318, expanded = false).x)
            assertEquals(240, place(318, expanded = false).y)
        }
    }
    @Test fun leftEdgeAndInteriorAnchorsDoNotInheritMenuClamp() {
        for (x in listOf(0, 8, 110, 220, 318)) {
            place(x)
            assertEquals(x, place(x, expanded = false).x)
        }
        assertFalse(place(0).menuBeforeBubble)
    }
    @Test fun expandedPlacementNeverChangesSideBecauseItsWindowMovedLeft() {
        val open = place(318)
        assertTrue(open.x < 180)
        assertTrue(place(318).menuBeforeBubble)
    }
    @Test fun cornersClampMenuButRestoreBubbleHeight() {
        for (x in listOf(0, 318)) for (y in listOf(0, 758)) {
            val open = place(x, y)
            assertTrue(open.y >= 0 && open.y + open.height <= 800)
            assertEquals(y, place(x, y, expanded = false).y)
        }
    }
    @Test fun narrowScreenConstrainsMenuInsteadOfMovingCollapsedBubble() {
        val open = place(198, width = 240)
        assertEquals(240, open.width)
        assertEquals(186, open.menuWidth)
        assertEquals(198, place(198, expanded = false, width = 240).x)
    }
    @Test fun liveStatusAndRotationUseIndependentAnchor() {
        assertEquals(152, place(318, menuHeight = 144).height)
        assertEquals(318, place(318, expanded = false, menuHeight = 144).x)
        val landscape = place(758, width = 800, height = 360)
        assertTrue(landscape.menuBeforeBubble)
        assertEquals(758, place(758, expanded = false, width = 800, height = 360).x)
    }
    @Test fun dragCanKeepCurrentMenuOrderingUntilFingerLifts() {
        val drag = BubblePlacement.layout(50, 100, 360, 800, 42, 245, 120, 4, 4, true, true)
        assertTrue(drag.menuBeforeBubble)
        assertEquals(50, place(50, expanded = false).x)
    }
}
