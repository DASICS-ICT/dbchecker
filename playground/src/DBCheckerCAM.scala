package DBChecker

import chisel3._
import chisel3.util._

// 32-entry fully-associative register CAM + LUTRAM counter for TX auto-release byte tracking
class DBCheckerCAM(numEntries: Int, keyWidth: Int) extends Module {
  val io = IO(new Bundle {
    // CAM access (pipeline muxes between W/R channels)
    val lookup_key   = Input(UInt(keyWidth.W))
    val lookup_valid = Output(Bool())
    val lookup_id    = Output(UInt(log2Up(numEntries).W))

    val insert_key   = Input(UInt(keyWidth.W))
    val insert_valid = Input(Bool())
    val insert_id    = Output(UInt(log2Up(numEntries).W))

    val remove_key   = Input(UInt(keyWidth.W))
    val remove_valid = Input(Bool())

    // Counter access (shared, pipeline handles W/R arbitration)
    val counter_slot   = Input(UInt(log2Up(numEntries).W))
    val counter_bytes  = Input(UInt(8.W))
    val counter_init_target = Input(UInt(48.W))
    val counter_update = Input(Bool())
    val auto_clear     = Output(Bool())

    // Control / status
    val clear_all  = Input(Bool())
    val used_slots = Output(UInt(log2Up(numEntries + 1).W))
    val cam_full   = Output(Bool())
  })

  // --- Register CAM ---
  val keys    = Reg(Vec(numEntries, UInt(keyWidth.W)))
  val valids  = RegInit(VecInit(Seq.fill(numEntries)(false.B)))

  // Parallel comparators
  val matches = Wire(Vec(numEntries, Bool()))
  for (i <- 0 until numEntries) {
    matches(i) := valids(i) && keys(i) === io.lookup_key
  }

  io.lookup_valid := matches.reduce(_ || _)
  io.lookup_id    := PriorityEncoder(matches)

  // Insert: find first free slot
  val free_slots = VecInit((0 until numEntries).map(i => !valids(i)))
  val insert_id  = PriorityEncoder(free_slots)
  io.insert_id   := insert_id
  when(io.insert_valid) {
    keys(insert_id)   := io.insert_key
    valids(insert_id) := true.B
  }

  // --- Counter LUTRAM (128 * 96-bit → ~192 LUT6 SLICEM) ---
  // LUTRAM has no reset/initial value: we must detect first access per slot
  // and initialize before accumulating, otherwise X propagates forever.
  val counters = Mem(numEntries, new CounterEntry)
  val counter_initialized = RegInit(VecInit(Seq.fill(numEntries)(false.B)))

  val counter_data = counters.read(io.counter_slot)
  val new_count    = counter_data.count + io.counter_bytes

  // auto_clear is registered to eliminate combinational glitches.
  // The LUTRAM read → add → compare path races against counter_slot changes
  // when counter_update deasserts (both switch on the same pipeline mux edge).
  val auto_clear_reg = RegInit(false.B)

  when(io.counter_update) {
    val entry = Wire(new CounterEntry)
    when(counter_initialized(io.counter_slot)) {
      // Normal accumulate: read-modify-write
      entry.count  := new_count
      entry.target := counter_data.target
      auto_clear_reg := new_count >= counter_data.target
    }.otherwise {
      // First access after allocation: LUTRAM contents are X, ignore them.
      // Initialize count to this beat's bytes (not 0, so first beat is counted).
      entry.count  := io.counter_bytes
      entry.target := io.counter_init_target
      counter_initialized(io.counter_slot) := true.B
      auto_clear_reg := false.B
    }
    counters.write(io.counter_slot, entry)
  }.otherwise {
    auto_clear_reg := false.B
  }

  io.auto_clear := auto_clear_reg

  // --- Remove: also clear counter_initialized so re-allocation re-initializes ---
  when(io.remove_valid) {
    for (i <- 0 until numEntries) {
      when(keys(i) === io.remove_key && valids(i)) {
        valids(i) := false.B
        counter_initialized(i) := false.B
      }
    }
  }

  // --- Clear all ---
  when(io.clear_all) {
    valids := VecInit(Seq.fill(numEntries)(false.B))
    counter_initialized := VecInit(Seq.fill(numEntries)(false.B))
  }

  // --- Status outputs ---
  io.used_slots := PopCount(valids)
  io.cam_full   := valids.reduce(_ && _)
}
