package com.example.catlifepet.floating

import org.junit.Assert.assertEquals
import org.junit.Test

class CatStateManagerTest {
    @Test
    fun `listeners receive initial state and every transition in order`() {
        val manager = CatStateManager(logger = {})
        val observed = mutableListOf<CatState>()

        manager.addListener(observed::add)
        manager.switchTo(CatState.DRINKING)
        manager.switchTo(CatState.HAPPY)
        manager.switchTo(CatState.IDLE)

        assertEquals(
            listOf(CatState.IDLE, CatState.DRINKING, CatState.HAPPY, CatState.IDLE),
            observed
        )
        assertEquals(CatState.IDLE, manager.currentState)
    }

    @Test
    fun `same-state trigger re-notifies so one-shot animations can replay`() {
        val manager = CatStateManager(logger = {})
        val observed = mutableListOf<CatState>()
        manager.addListener(observed::add)

        manager.switchTo(CatState.CUDDLE)
        manager.switchTo(CatState.CUDDLE)

        assertEquals(listOf(CatState.IDLE, CatState.CUDDLE, CatState.CUDDLE), observed)
        assertEquals(CatState.CUDDLE, manager.currentState)
    }

    @Test
    fun `removed listeners stop receiving state changes`() {
        val manager = CatStateManager(logger = {})
        val observed = mutableListOf<CatState>()
        val listener: (CatState) -> Unit = observed::add

        manager.addListener(listener)
        manager.removeListener(listener)
        manager.switchTo(CatState.SLEEPING)

        assertEquals(listOf(CatState.IDLE), observed)
    }
}
