package pro.masterdoc.dashboard

import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class WorkOrderStoreContractTest {
    @Test
    fun storeRequiresNonNullDataSource() {
        val constructors = WorkOrderStore::class.java.declaredConstructors
        val dataSourceConstructor = constructors.single { it.parameterCount == 1 }

        assertFalse(constructors.any { it.parameterCount == 0 })
        assertFalse(constructors.any { it.parameterCount > 1 })
        assertEquals(DataSource::class.java, dataSourceConstructor.parameterTypes.single())
    }
}
