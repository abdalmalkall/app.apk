package com.naveenapps.expensemanager.core.data.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.naveenapps.expensemanager.core.common.utils.AppCoroutineDispatchers
import com.naveenapps.expensemanager.core.database.ExpenseManagerDatabase
import com.naveenapps.expensemanager.core.model.Customer
import com.naveenapps.expensemanager.core.model.Resource
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CustomerRepositoryImplTest {

    private lateinit var database: ExpenseManagerDatabase
    private lateinit var repository: CustomerRepositoryImpl

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            ExpenseManagerDatabase::class.java,
        ).allowMainThreadQueries().build()
        repository = CustomerRepositoryImpl(
            database.customerDao(),
            AppCoroutineDispatchers(Dispatchers.Unconfined, Dispatchers.Unconfined, Dispatchers.Unconfined),
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `customer can be created and updated without losing profile fields`() = runBlocking {
        val createdAt = Date(1_700_000_000_000L)
        val customer = Customer(
            id = "customer-1",
            name = "Ahmed",
            phone = " 555-0100 ",
            address = " Main Street ",
            notes = "  Wholesale buyer  ",
            creditLimitMilliunits = 125_500L,
            createdOn = createdAt,
            updatedOn = createdAt,
        )

        assertThat(repository.saveCustomer(customer)).isEqualTo(Resource.Success(true))
        val edited = customer.copy(name = "Ahmed Ali", createdOn = Date(0), updatedOn = Date(createdAt.time + 1_000L))
        assertThat(repository.saveCustomer(edited)).isEqualTo(Resource.Success(true))

        val stored = repository.getAllCustomers().first().single()
        assertThat(stored.name).isEqualTo("Ahmed Ali")
        assertThat(stored.phone).isEqualTo("555-0100")
        assertThat(stored.address).isEqualTo("Main Street")
        assertThat(stored.notes).isEqualTo("Wholesale buyer")
        assertThat(stored.creditLimitMilliunits).isEqualTo(125_500L)
        assertThat(stored.createdOn).isEqualTo(createdAt)
    }

    @Test
    fun `duplicate names and negative credit limits are rejected`() = runBlocking {
        val timestamp = Date(1_700_000_000_000L)
        val first = Customer("customer-1", "Ahmed", createdOn = timestamp, updatedOn = timestamp)
        val duplicate = Customer("customer-2", " ahMED ", createdOn = timestamp, updatedOn = timestamp)
        val invalidLimit = Customer(
            "customer-3",
            "Zaid",
            creditLimitMilliunits = -1L,
            createdOn = timestamp,
            updatedOn = timestamp,
        )

        assertThat(repository.saveCustomer(first)).isEqualTo(Resource.Success(true))
        assertThat(repository.saveCustomer(duplicate)).isInstanceOf(Resource.Error::class.java)
        assertThat(repository.saveCustomer(invalidLimit)).isInstanceOf(Resource.Error::class.java)
        assertThat(repository.getAllCustomers().first()).hasSize(1)
    }
}
