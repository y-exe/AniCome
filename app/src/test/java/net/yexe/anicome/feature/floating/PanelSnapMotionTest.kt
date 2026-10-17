package net.yexe.anicome

import org.junit.Assert.*
import org.junit.Test

class PanelSnapMotionTest {
    private class Driver : PanelAnimator {
        data class Run(val from: Float, val to: Float, val duration: Long, val frame: (Float) -> Unit, val end: () -> Unit, var cancelled: Boolean = false)
        val runs = mutableListOf<Run>()
        override fun start(from: Float, to: Float, duration: Long, frame: (Float) -> Unit, finished: () -> Unit): PanelAnimation {
            val run = Run(from, to, duration, frame, finished); runs += run
            return PanelAnimation { run.cancelled = true; finished() }
        }
    }
    @Test fun releasesAnimateOnlyHorizontallyAndSaveTheFinalEdgeOnce() {
        val driver = Driver(); val motion = PanelSnapMotion(driver)
        val frames = mutableListOf<PanelPosition>(); val saves = mutableListOf<PanelPosition>()
        motion.settle(.3f, .7f, true, false, frames::add, saves::add)
        val run = driver.runs.single()
        assertEquals(.3f, run.from); assertEquals(0f, run.to); assertEquals(180L, run.duration)
        run.frame(.2f); run.frame(.1f); assertTrue(saves.isEmpty())
        assertEquals(listOf(PanelPosition(.2f, .7f), PanelPosition(.1f, .7f)), frames)
        run.end(); run.end()
        assertEquals(listOf(PanelPosition(0f, .7f)), saves)
    }
    @Test fun newDragOrRotationCancelsOldFramesAndDoesNotSaveTheOldOrientation() {
        val driver = Driver(); val motion = PanelSnapMotion(driver)
        val frames = mutableListOf<PanelPosition>(); val saves = mutableListOf<PanelPosition>()
        motion.settle(.6f, .4f, true, false, frames::add, saves::add)
        val old = driver.runs.single(); old.frame(.7f)
        motion.cancel(); old.frame(1f); old.end()
        assertTrue(old.cancelled); assertTrue(saves.isEmpty()); assertEquals(listOf(PanelPosition(.7f, .4f)), frames)
        motion.settle(.2f, .8f, true, false, frames::add, saves::add)
        old.frame(.9f); old.end(); driver.runs.last().end()
        assertEquals(listOf(PanelPosition(0f, .8f)), saves)
    }
    @Test fun disabledSnapAndReducedMotionDoNotStartAnAnimator() {
        val driver = Driver(); val motion = PanelSnapMotion(driver)
        val saves = mutableListOf<PanelPosition>()
        motion.settle(.4f, .7f, false, false, {}, saves::add)
        motion.settle(.6f, .2f, true, true, {}, saves::add)
        assertTrue(driver.runs.isEmpty()); assertEquals(listOf(PanelPosition(.4f, .7f), PanelPosition(1f, .2f)), saves)
    }
    @Test fun lateFramesCannotOverrideTheNextReleaseAndInvalidFramesStayOutOfTheWindow() {
        val driver = Driver(); val motion = PanelSnapMotion(driver)
        val frames = mutableListOf<PanelPosition>(); val saves = mutableListOf<PanelPosition>()
        motion.settle(.2f, .3f, true, false, frames::add, saves::add)
        val old = driver.runs.single()
        motion.settle(.8f, .7f, true, false, frames::add, saves::add)
        old.end(); old.frame(-1f)
        val next = driver.runs.last(); next.frame(Float.NaN); next.frame(2f); next.end()
        assertTrue(old.cancelled); assertEquals(listOf(PanelPosition(1f, .7f)), saves)
        assertTrue(frames.all { it.x in 0f..1f && it.y == .7f })
    }
    @Test fun synchronousAnimationCompletionIsNotSavedTwiceOnCancel() {
        var cancellations = 0; var saved = 0
        val motion = PanelSnapMotion(object : PanelAnimator {
            override fun start(from: Float, to: Float, duration: Long, frame: (Float) -> Unit, finished: () -> Unit): PanelAnimation {
                finished(); return PanelAnimation { cancellations++; finished() }
            }
        })
        motion.settle(.4f, .5f, true, false, {}, { saved++ }); motion.cancel()
        assertEquals(1, saved); assertEquals(1, cancellations)
    }
}
