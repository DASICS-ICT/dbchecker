package axi

import chisel3._
import chisel3.util._

object AXILiteState extends ChiselEnum {
  val Idle, readData, writeData, writeResp = Value
}

class AXILiteRF(addrWidth: Int = 32, dataWidth: Int = 32, Depth: Int = 4) extends Module {

  require(dataWidth == 32 || dataWidth == 64, "AxiLite `dataWidth` must be 32 or 64") 
  // require(Depth >= 2 && (Depth&(Depth-1)) == 0, "Depth must be a power of 2") 
  val io = IO(new AxiLiteSlave(addrWidth,dataWidth))

  // Register File: 4 x 32-bit registers
  val regFile = RegInit(VecInit(Seq.fill(Depth)(0.U(dataWidth.W))))
  
  // FSM for AXI4-Lite control
  val state = RegInit(AXILiteState.Idle)
  
  // Internal signals
  val readAddrReg  = Reg(UInt(addrWidth.W))
  val writeAddrReg = Reg(UInt(addrWidth.W))
  val writeDataReg = Reg(UInt(dataWidth.W))
  val writeStrbReg = Reg(UInt((dataWidth / 8).W))
  // Default outputs
  io.aw.ready     := false.B
  io.w.ready     := false.B
  io.b.valid     := false.B
  io.b.bits.resp := 0.U  // OKAY
  io.ar.ready      := false.B
  io.r.valid      := false.B
  io.r.bits.resp  := 0.U  // OKAY
  io.r.bits.data  := 0.U

  // FSM logic
  switch(state) {
    is(AXILiteState.Idle) {
      // Priority to write over read
      when(io.aw.valid && io.w.valid) {
        // Capture write address and data
        writeAddrReg := io.aw.bits.addr
        writeDataReg := io.w.bits.data
        writeStrbReg := io.w.bits.strb
        io.aw.ready := true.B
        io.w.ready := true.B
        state := AXILiteState.writeData
      }.elsewhen(io.ar.valid) {
        // Capture read address
        readAddrReg := io.ar.bits.addr
        io.ar.ready := true.B
        state := AXILiteState.readData
      }
    }
    
    is(AXILiteState.readData) {
      io.r.valid := true.B
      // Address decoding for read
      val index = readAddrReg(
        log2Ceil((dataWidth / 8)) + log2Ceil(Depth) - 1, 
        log2Ceil((dataWidth / 8)))  // aligned
      when(index < Depth.U && readAddrReg(log2Ceil((dataWidth / 8)) - 1,0) === 0.U) {
        io.r.bits.data := regFile(index)
      }.otherwise {
        io.r.bits.resp := 2.U  // SLVERR for invalid address
      }
      when(io.r.ready) {
        state := AXILiteState.Idle
      }
    }
    
    is(AXILiteState.writeData) {
      // Perform write operation
      val index = writeAddrReg(
        log2Ceil((dataWidth / 8)) + log2Ceil(Depth) - 1, 
        log2Ceil((dataWidth / 8)))  // aligned
      when(index < Depth.U && readAddrReg(log2Ceil((dataWidth / 8)) - 1,0) === 0.U) {
        val writeDataResult = Wire(UInt(dataWidth.W))
        // Byte-wise write using w.bits.strb
        for (i <- 0 until (dataWidth / 8)) {
          when(writeStrbReg(i)) {
            writeDataResult := writeDataReg(8*i+7, 8*i)
          }.otherwise{
            writeDataResult := regFile(index)(8*i+7, 8*i)
          }
        }
        regFile(index) := writeDataResult
      }.otherwise {
        io.b.bits.resp := 2.U  // SLVERR for invalid address
      }
      state := AXILiteState.writeResp
    }
    
    is(AXILiteState.writeResp) {
      io.b.valid := true.B
      when(io.b.ready) {
        state := AXILiteState.Idle
      }
    }
  }
}