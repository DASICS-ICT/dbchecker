package DBChecker

import chisel3._
import chisel3.util._
import axi._

class DBChecker extends Module with DBCheckerConst {
  val m_axi_io_rx = IO(new AxiMaster(64, 128, idWidth = 5))
  val s_axi_io_rx = IO(new AxiSlave(64, 128, idWidth = 5))
  val m_axi_dbte = IO(new AxiMaster(48, 128))
  val s_axil_ctrl = IO(new AxiLiteSlave(32, 32))
  val debug_if = IO(new Bundle {
    val flow = Output(UInt(128.W))
    val ctrl = Output(UInt(128.W))
  })

  val ctrl = Module(new DBCheckerCtrl)
  val handler = Module(new DBCheckerPipeline)
  val cache = Module(new DBCheckerCache)
  val refill = Module(new DBCheckerRefill)
  ctrl.s_axil <> s_axil_ctrl
  handler.s_axi_io_rx <> s_axi_io_rx
  handler.m_axi_io_rx <> m_axi_io_rx
  refill.m_axi <> m_axi_dbte
  handler.lookup_req <> cache.lookup_req
  handler.lookup_done <> cache.lookup_done
  handler.refill_req <> refill.req
  handler.refill_done <> refill.done
  refill.fill_offer <> cache.fill_offer
  refill.fill_take := cache.fill_take
  cache.free_start := ctrl.free_start
  cache.free_window := ctrl.free_window
  refill.free_window := ctrl.free_window
  handler.free_window := ctrl.free_window
  ctrl.cache_free_done := cache.free_done
  handler.cache_ready := cache.cache_ready
  handler.enable_mask := ctrl.enable_mask
  refill.base := ctrl.dbte_base
  refill.limit := ctrl.refill_limit
  ctrl.error <> handler.error
  ctrl.pipeline_idle := handler.idle
  ctrl.refill_idle := refill.idle
  ctrl.occupancy := handler.occupancy
  ctrl.outstanding := refill.outstanding
  ctrl.events := handler.events
  ctrl.events.ar := refill.ar_event
  ctrl.events.r := refill.r_event
  ctrl.events.refill_done := refill.done.valid
  debug_if.flow := handler.debug_if
  debug_if.ctrl := ctrl.debug_if
}
