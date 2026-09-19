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

@Entity(tableName = "signal_logs")
data class SignalLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val symbol: String,                 // e.g. "BTCIDR"
    val action: String,                 // "BUY", "SELL", "HOLD"
    val strategyMode: String = "SCALPING", // "SCALPING", "SWING", "OFFICE_DAILY", "SECOND_WAVE", "TRENCHING"
    val confidence: Int,                // Confidence score at firing time (0-100)
    val sentiment: String = "",         // e.g. "BULLISH_REVERSAL"
    val entryPrice: Double,             // Price at time of firing
    val targetPrice1: Double = 0.0,
    val targetPrice2: Double = 0.0,
    val stopLoss: Double = 0.0,
    val firedAt: Long = System.currentTimeMillis(),
    val reasoning: String = "",         // Text explanation at firing
    val scalpingStage: String = "",     // e.g. "ENTRY", "STRONG_ENTRY"
    
    // Performance Outcome Tracking
    val outcomeStatus: String = "TRACKING", // "TRACKING", "HIT_TP1", "HIT_TP2", "HIT_SL", "INVALIDATED", "EXPIRED", "MANUAL_WIN", "MANUAL_LOSS"
    val maxProfitPct: Double = 0.0,         // Highest positive % reached during tracking
    val maxDrawdownPct: Double = 0.0,       // Worst negative % reached during tracking
    val exitPrice: Double? = null,          // Final price when resolved
    val realizedPnlPct: Double? = null,     // Realized profit/loss % outcome
    val peakPrice: Double = 0.0,            // Highest price observed
    val troughPrice: Double = 0.0,          // Lowest price observed
    val resolvedAt: Long? = null,           // Timestamp when resolved
    val resolutionNote: String? = null      // Detail note e.g. "Hit TP1 at Rp 1.520.000.000 (+2.8%)"
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

@Dao
interface SignalLogDao {
    @Query("SELECT * FROM signal_logs ORDER BY firedAt DESC")
    fun getAllLogsFlow(): Flow<List<SignalLogEntity>>

    @Query("SELECT * FROM signal_logs WHERE symbol = :symbol ORDER BY firedAt DESC")
    fun getLogsBySymbolFlow(symbol: String): Flow<List<SignalLogEntity>>

    @Query("SELECT * FROM signal_logs WHERE outcomeStatus = 'TRACKING' ORDER BY firedAt DESC")
    fun getActiveTrackingLogsFlow(): Flow<List<SignalLogEntity>>

    @Query("SELECT * FROM signal_logs WHERE outcomeStatus = 'TRACKING'")
    suspend fun getActiveTrackingLogs(): List<SignalLogEntity>

    @Query("SELECT * FROM signal_logs WHERE symbol = :symbol AND outcomeStatus = 'TRACKING'")
    suspend fun getActiveTrackingLogsForSymbol(symbol: String): List<SignalLogEntity>

    @Query("SELECT * FROM signal_logs WHERE symbol = :symbol ORDER BY firedAt DESC LIMIT 1")
    suspend fun getLatestLogForSymbol(symbol: String): SignalLogEntity?

    @Query("SELECT * FROM signal_logs WHERE id = :id")
    suspend fun getLogById(id: Long): SignalLogEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLog(log: SignalLogEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLogs(logs: List<SignalLogEntity>)

    @Update
    suspend fun updateLog(log: SignalLogEntity)

    @Query("DELETE FROM signal_logs WHERE id = :id")
    suspend fun deleteLogById(id: Long)

    @Query("DELETE FROM signal_logs")
    suspend fun clearAllLogs()

    @Query("SELECT COUNT(*) FROM signal_logs")
    suspend fun getLogCount(): Int
}

@Database(
    entities = [RealTradeEntity::class, RealOpenOrderEntity::class, SignalLogEntity::class, TradeHistoryRecordEntity::class],
    version = 5,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun realTradeDao(): RealTradeDao
    abstract fun signalLogDao(): SignalLogDao
    abstract fun tradeHistoryRecordDao(): TradeHistoryRecordDao

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
