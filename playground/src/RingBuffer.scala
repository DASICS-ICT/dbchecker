package DBChecker

import chisel3._
import chisel3.util._
import axi._

class RingBuffer[T <: Data](gen: T, depth: Int) extends Module {
  val io = IO(new Bundle {
    val enq = Flipped(Decoupled(gen.cloneType))     // 入队接口
    val deq = Decoupled(gen.cloneType)              // 出队接口
    val count = Output(UInt(log2Ceil(depth+1).W))   // 当前元素数量
    val empty = Output(Bool())                      // 空标志
    val full = Output(Bool())                       // 满标志
  })

  require(depth > 1, "RingBuffer depth must be greater than 1")
  require(isPow2(depth), "RingBuffer depth must be power of 2")
  

  val buffer = RegInit(VecInit(Seq.fill(depth)(0.U.asTypeOf(gen.cloneType))))
  val buffer_valid = RegInit(VecInit(Seq.fill(depth)(false.B)))
  
  // 写指针：指向下一个要写入的buffer
  val writePtr = RegInit(0.U.asTypeOf(UInt(log2Ceil(depth).W)))
  // 读指针：指向下一个要读出的buffer  
  val readPtr  = RegInit(0.U.asTypeOf(UInt(log2Ceil(depth).W)))
  
  // 计算当前数量
   val count = PopCount(buffer_valid)
  
  // 输出状态
  io.count := count
  io.empty := count === 0.U
  io.full := count === depth.U
  
  // 写入逻辑
  val canWrite = !buffer_valid(writePtr)
  io.enq.ready := canWrite
  
  when(io.enq.valid && io.enq.ready) {
    buffer(writePtr) := io.enq.bits
    buffer_valid(writePtr) := true.B
    writePtr := (writePtr + 1.U) % depth.U  // 切换写指针
  }
  
  // 读出逻辑
  val canRead = buffer_valid(readPtr)
  io.deq.valid := canRead
  
  // 选择读出数据
  io.deq.bits := buffer(readPtr)
  
  when(io.deq.valid && io.deq.ready) {
    buffer_valid(readPtr) := false.B
    readPtr := (readPtr + 1.U) % depth.U // 切换读指针
  }
}

// 提供工厂方法
object RingBuffer {
  def apply[T <: Data](gen: T, depth: Int): RingBuffer[T] = 
    new RingBuffer(gen, depth)
}