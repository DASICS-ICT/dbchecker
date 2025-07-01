package DBChecker

import chisel3._
import axi._

class DBChecker extends Module {
  val m_axi_dma = IO(new AxiMaster(36,128))
  val s_axi_dma = IO(new AxiSlave(36,128))
  val s_axil_ctrl = IO(new AxiLiteSlave(32, 32))

  m_axi_dma <> s_axi_dma

  val ar_buf = Module(new RingBuffer(new AxiAddr(36),2))
  val r_buf = Module(new RingBuffer(new AxiReadData(128),2))
  val aw_buf = Module(new RingBuffer(new AxiAddr(36),2))
  val w_buf = Module(new RingBuffer(new AxiWriteData(128),2))
  val b_buf = Module(new RingBuffer(new AxiWriteResp(),2))

  ar_buf.io.enq <> s_axi_dma.ar
  ar_buf.io.deq <> m_axi_dma.ar
  r_buf.io.enq <> m_axi_dma.r
  r_buf.io.deq <> s_axi_dma.r
  aw_buf.io.enq <> s_axi_dma.aw
  aw_buf.io.deq <> m_axi_dma.aw
  w_buf.io.enq <> s_axi_dma.w
  w_buf.io.deq <> m_axi_dma.w
  b_buf.io.enq <> m_axi_dma.b
  b_buf.io.deq <> s_axi_dma.b

  val rf = Module(new AXILiteRF(32, 32, 4))
  rf.s_axil <> s_axil_ctrl
}
