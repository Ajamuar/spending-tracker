package com.myexpenses.di

import android.content.Context
import androidx.room.Room
import com.myexpenses.data.local.AccountDao
import com.myexpenses.data.local.AppDatabase
import com.myexpenses.data.local.BudgetDao
import com.myexpenses.data.local.CategoryDao
import com.myexpenses.data.local.IngestionDao
import com.myexpenses.data.local.TransactionDao
import com.myexpenses.security.DatabaseKeyManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides
    @Singleton
    fun provideDatabase(
        @ApplicationContext context: Context,
        keyManager: DatabaseKeyManager,
    ): AppDatabase {
        System.loadLibrary("sqlcipher")
        val factory = SupportOpenHelperFactory(keyManager.getOrCreatePassphrase())
        return Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            context.getDatabasePath("my-expenses.db").absolutePath,
        )
            .openHelperFactory(factory)
            .build()
    }

    @Provides fun provideTransactionDao(database: AppDatabase): TransactionDao =
        database.transactionDao()

    @Provides fun provideCategoryDao(database: AppDatabase): CategoryDao =
        database.categoryDao()

    @Provides fun provideAccountDao(database: AppDatabase): AccountDao =
        database.accountDao()

    @Provides fun provideBudgetDao(database: AppDatabase): BudgetDao =
        database.budgetDao()

    @Provides fun provideIngestionDao(database: AppDatabase): IngestionDao =
        database.ingestionDao()
}
