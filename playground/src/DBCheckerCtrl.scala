package DBChecker

import chisel3._
import chisel3.util._
import axi._

class DBCheckerCtrl extends Module with DBCheckerConst {
  assert(RegNum >= 2, "RegNum must be at least 2")
  // io
  val s_axil       = IO(new AxiLiteSlave(32, 32))
  // val m_axi        = IO(new AxiMaster(32, 128))
  val ctrl_reg     = IO(Output(Vec(RegNum, UInt(32.W))))
  val dbte_sram_w  = IO(Flipped(new MemoryWritePort(UInt(128.W), log2Up(dbte_num), false)))
  val dbte_sram_r  = IO(Flipped(new MemoryReadPort(UInt(128.W), log2Up(dbte_num))))
  val err_req_r    = IO(Flipped(Decoupled(new DBCheckerErrReq)))
  val err_req_w    = IO(Flipped(Decoupled(new DBCheckerErrReq)))

  val debug_if = IO(Output(UInt(128.W)))

  // register file r/w logic
  val regFile = RegInit(VecInit(Seq.fill(RegNum)(0.U(32.W))))
  ctrl_reg := regFile

  // FSM for AXI4-Lite control
  val state = RegInit(CtrlState.Idle)

  // Internal signals
  val readAddrReg  = RegInit(0.U(32.W))
  val writeAddrReg = RegInit(0.U(32.W))
  val writeDataReg = RegInit(0.U(32.W))
  val writeStrbReg = RegInit(0.U(4.W))
  // Default outputs
  s_axil.aw.ready    := false.B
  s_axil.w.ready     := false.B
  s_axil.b.valid     := false.B
  s_axil.b.bits.resp := 0.U // OKAY
  s_axil.ar.ready    := false.B
  s_axil.r.valid     := false.B
  s_axil.r.bits.resp := 0.U // OKAY
  s_axil.r.bits.data := 0.U

  // DBTE ram table, 128 bits each
  dbte_sram_r.address := 0.U
  dbte_sram_r.enable  := false.B

  dbte_sram_w.address := 0.U
  dbte_sram_w.data    := 0.U
  dbte_sram_w.enable  := false.B

  val clr_err_reg        = regFile(chk_clr_err)
  val err_cnt_reg        = regFile(chk_err_cnt)
  val err_cnt_reg_struct = err_cnt_reg.asTypeOf(new DBCheckerErrCnt)
  val err_info_reg       = regFile(chk_err_info)
  val err_addr_lo_reg    = regFile(chk_err_addr_lo)
  val err_addr_hi_reg    = regFile(chk_err_addr_hi)

  val totalSpaceSize = SramBaseAddr + dbte_num * 16
  val internalAddrWidth = log2Ceil(totalSpaceSize)
  def getOffset(addr: UInt): UInt = addr(internalAddrWidth - 1, 0)
  // Helper function to check address ranges
  def isSramAddr(addr: UInt): Bool = getOffset(addr) >= SramBaseAddr.U
  // Calculate SRAM index: (Addr - Base) / 16 bytes
  def getSramIndex(addr: UInt): UInt = (getOffset(addr) - SramBaseAddr.U) >> 4
  // Calculate 32-bit sub-word index within 128-bit: ((Addr - Base) % 16) / 4
  def getSubWordIndex(addr: UInt): UInt = ((getOffset(addr) - SramBaseAddr.U)(3, 0)) >> 2
  
  // FSM logic for access reg & sram
  switch(state) {
    is(CtrlState.Idle) {
      // Priority to write over read
      when(s_axil.aw.valid && s_axil.w.valid) {
        // Capture write address and data
        writeAddrReg    := s_axil.aw.bits.addr
        writeDataReg    := s_axil.w.bits.data
        writeStrbReg    := s_axil.w.bits.strb
        s_axil.aw.ready := true.B
        s_axil.w.ready  := true.B
        
        when(isSramAddr(s_axil.aw.bits.addr)) {
          // Write to SRAM
          dbte_sram_r.address := getSramIndex(s_axil.aw.bits.addr)
          dbte_sram_r.enable  := true.B
          state := CtrlState.writeSramRMW
        }.otherwise {
          // Write to register file
          state := CtrlState.writeReg
        }

      }.elsewhen(s_axil.ar.valid) {
        // Capture read address
        readAddrReg     := s_axil.ar.bits.addr
        s_axil.ar.ready := true.B
        
        when(isSramAddr(s_axil.ar.bits.addr)) {
          // Start Read for SRAM
          dbte_sram_r.address := getSramIndex(s_axil.ar.bits.addr)
          dbte_sram_r.enable  := true.B
          state := CtrlState.readSram
        }.otherwise {
          state := CtrlState.readReg
        }
      }
    }

    is(CtrlState.readReg) {
      s_axil.r.valid := true.B
      // Address decoding for read
      val index = readAddrReg(log2Up(RegNum) + 1, 2)
      when(index < RegNum.U && readAddrReg(1, 0) === 0.U) {
        // read logic
        s_axil.r.bits.data := regFile(index)
      }.otherwise {
        s_axil.r.bits.data := 0.U
        s_axil.r.bits.resp := 0.U // fake SLVERR for invalid address
      }
      when(s_axil.r.ready) {
        state := CtrlState.Idle
      }
    }

    is(CtrlState.readSram) {
      // SRAM data is available in this cycle (assuming 1 cycle latency)
      s_axil.r.valid := true.B
     // Select the correct 32-bit chunk from 128-bit data
      val subIdx = getSubWordIndex(readAddrReg)
      val dataChunks = VecInit(Seq.tabulate(4) { i => dbte_sram_r.data(32 * i + 31, 32 * i) })

      s_axil.r.bits.data := dataChunks(subIdx)
      s_axil.r.bits.resp := 0.U // OKAY

      when(s_axil.r.ready) {
        state := CtrlState.Idle
      }
    }

    is(CtrlState.writeReg) {
      // Perform write operations for Registers
      val index = writeAddrReg(log2Up(RegNum) + 1, 2) // aligned
      when(index < RegNum.U && writeAddrReg(1, 0) === 0.U) {
        // Byte-wise write using w.bits.strb
        val wmask = Cat((0 until 4).reverse.map(i => Fill(8, writeStrbReg(i))))
        when((index === chk_clr_err.U && !regFile(index)(0)) || 
              index === chk_en.U)
        {
          // write success
          regFile(index) := (regFile(index) & ~wmask) | (writeDataReg & wmask)
        }
      }.otherwise {
        s_axil.b.bits.resp := 0.U 
      }
      
      // when cmd is already valid, wait until it is processed
      when (!(index === chk_clr_err.U && regFile(index)(0))) {
        state := CtrlState.writeResp
      }
    }

    is(CtrlState.writeSramRMW) {
      // SRAM Read Data is valid now. 
      // Perform Read-Modify-Write combinationally and assert Write
      
      val subIdx = getSubWordIndex(writeAddrReg)
      val oldChunks = dbte_sram_r.data.asTypeOf(Vec(4, UInt(32.W)))
      val targetChunk = oldChunks(subIdx)
      // Calculate mask from strb
      val wmask = Cat((0 until 4).reverse.map(i => Fill(8, writeStrbReg(i))))
      
      // Modify the specific chunk
      val modifiedChunk = (targetChunk & ~wmask) | (writeDataReg & wmask)
      val newChunks = VecInit((0 until 4).map(i => 
        Mux(i.U === subIdx, modifiedChunk, oldChunks(i))
      ))
      
      // Write back to SRAM
      dbte_sram_w.address := getSramIndex(writeAddrReg)
      dbte_sram_w.data    := newChunks.asUInt
      dbte_sram_w.enable  := true.B
      
      // Move to response
      state := CtrlState.writeResp
    }

    is(CtrlState.writeResp) {
      s_axil.b.valid := true.B
      when(s_axil.b.ready) {
        state := CtrlState.Idle
      }
    }
  }

  // error reg handler
  when(clr_err_reg(0)) {
    // clear error counter and info
    err_cnt_reg     := 0.U
    err_info_reg    := 0.U
    err_addr_lo_reg := 0.U
    err_addr_hi_reg := 0.U
    // clear clr_err_reg
    regFile(chk_clr_err) := 0.U
  }

  val next_cnt    = Wire((new DBCheckerErrCnt).cnt.cloneType)
  val cnt_max_val = (1.U << next_cnt(0).getWidth) - 1.U

  for (i <- 0 until 4) {
    next_cnt(i) := 
      err_cnt_reg_struct.cnt(i) +& 
      (err_req_r.valid && err_req_r.bits.typ === i.U).asUInt +& 
      (err_req_w.valid && err_req_w.bits.typ === i.U).asUInt
  }
  val err_latest = Mux(err_req_r.valid, 1.U << err_req_r.bits.typ, 1.U << err_req_w.bits.typ)
  val cnt_enable = !(clr_err_reg(0))
  when(cnt_enable && (err_req_r.valid || err_req_w.valid)) { // not clr_err
    err_cnt_reg  := Cat(next_cnt.asUInt, err_latest)
    err_info_reg := Mux(err_req_r.valid, err_req_r.bits.info, err_req_w.bits.info)
    err_addr_hi_reg := Mux(err_req_r.valid, err_req_r.bits.addr(63,32), err_req_w.bits.addr(63,32))
    err_addr_lo_reg := Mux(err_req_r.valid, err_req_r.bits.addr(31, 0), err_req_w.bits.addr(31, 0))
  }
  err_req_r.ready := cnt_enable
  err_req_w.ready := cnt_enable

  val state_padded = WireInit(UInt(32.W), state.asUInt)
  debug_if := Cat(state_padded, err_info_reg, err_addr_hi_reg, err_addr_lo_reg)
}
