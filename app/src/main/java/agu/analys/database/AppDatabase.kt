package agu.analys.database

import android.content.Context
import androidx.room.*
import agu.analys.AppContextProvider
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "real_trades")
data class RealTradeEntity(
    @PrimaryKey val id: String, // trade_id or order_id
    val symbol: String,         // e.g. "btcidr"
    val price: Double,
    val qty: Double,
    val amount: Double,         // price * qty
    val time: Long,             // timestamp
    val side: String,           // "BUY" or "SELL"
    val isBuyer: Boolean,
    val strategyMode: String = "MANUAL",
    val holdingDurationMs: Long? = null,
    val entryPrice: Double? = null,
    val entryTimestamp: Long? = null,
    val pnlIdr: Double? = null,
    val pnlPercent: Double? = null,
    val isTrailingUsed: Boolean = false,
    val trailingPercent: Double? = null,
    val trailingPeakPrice: Double? = null,
    val trailingLockPrice: Double? = null,
    val signalSnapshotJson: String? = null
)

@Entity(tableName = "real_open_orders")
data class RealOpenOrderEntity(
    @PrimaryKey val orderId: String,
    val symbol: String,
    val side: String, // "BUY" or "SELL"
    val type: String, // "LIMIT" or "MARKET"
    val price: Double,
    val quantity: Double,
    val executedQty: Double,
    val status: String,
    val time: Long
)

@Dao
interface RealTradeDao {
    @Query("SELECT * FROM real_trades ORDER BY time DESC")
    fun getAllTradesFlow(): Flow<List<RealTradeEntity>>

    @Query("SELECT * FROM real_trades WHERE symbol = :symbol ORDER BY time DESC")
    fun getTradesBySymbol(symbol: String): List<RealTradeEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTrades(trades: List<RealTradeEntity>)

    @Query("DELETE FROM real_trades")
    suspend fun clearAllTrades()

    // Real Open Orders Cache (Offline/Caching Support)
    @Query("SELECT * FROM real_open_orders ORDER BY time DESC")
    fun getOpenOrdersFlow(): Flow<List<RealOpenOrderEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOpenOrders(orders: List<RealOpenOrderEntity>)

    @Query("DELETE FROM real_open_orders")
    suspend fun clearOpenOrders()

    @Query("DELETE FROM real_open_orders WHERE orderId = :orderId")
    suspend fun deleteOpenOrderById(orderId: String)
}

@Database(entities = [RealTradeEntity::class, RealOpenOrderEntity::class], version = 2, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun realTradeDao(): RealTradeDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val context = AppContextProvider.context
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "agu_analys_db"
                )
                .fallbackToDestructiveMigration()
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
