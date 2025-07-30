package DBChecker

import chisel3._
import axi._

class DBChecker extends Module {
  val m_axi_io_tx = IO(new AxiMaster(32, 32))
  val s_axi_io_tx = IO(new AxiSlave(32, 32))
  val m_axi_io_rx = IO(new AxiMaster(36, 128))
  val s_axi_io_rx = IO(new AxiSlave(36, 128))
  val s_axil_ctrl = IO(new AxiLiteSlave(32, 32))


// ring buffer
  val rx_rb = Module(new AXIRingBuffer(36, 128, 16))
  rx_rb.m_axi <> m_axi_io_rx
  rx_rb.s_axi <> s_axi_io_rx

  val tx_rb = Module(new AXIRingBuffer(32, 32, 16))
  tx_rb.m_axi <> m_axi_io_tx
  tx_rb.s_axi <> s_axi_io_tx

// ctrl register
  val rf = Module(new AXILiteRF(32, 32, 4))
  rf.s_axil <> s_axil_ctrl
}
