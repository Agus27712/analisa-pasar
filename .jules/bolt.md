## 2026-09-27 - DecimalFormat & DecimalFormatSymbols Allocation Overhead in Market Data Streaming

**Learning:** `DecimalFormatSymbols` resource loading and `DecimalFormat` instantiation on every price or volume formatting call creates significant GC pressure in high-frequency trading apps receiving streaming tickers.
**Action:** Use static cached `DecimalFormatSymbols` and `ThreadLocal` cached `DecimalFormat` instances by pattern to achieve zero-allocation price formatting in hot paths.
