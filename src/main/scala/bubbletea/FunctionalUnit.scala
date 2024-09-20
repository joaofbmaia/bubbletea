package bubbletea

import chisel3._
import chisel3.util._

object FUSel extends ChiselEnum {
  // the reserved values are so that the enum can be easily converted to a UInt
  val nop, add, sub, mul, shl, lshr, ashr, and, or, xor, res0, res1, res2, res3, res4, res5 = Value
}

class FunctionalUnit[T <: Data: Arithmetic](params: BubbleteaParams[T]) (implicit ev: Arithmetic[T]) extends Module {
  import ev._

  val io = IO(new Bundle {
    val a = Input(params.dataType)
    val b = Input(params.dataType)
    val op = Input(FUSel())
    val result = Output(params.dataType)
  })

  io.result := DontCare

  val maxShiftBits = log2Ceil(params.dataType.getWidth)
  val truncatedB = io.b.asUInt(maxShiftBits - 1, 0)

  switch(io.op) {
    is(FUSel.nop) {
      io.result := io.a
    }
    is(FUSel.add) {
      io.result := io.a + io.b
    }
    is(FUSel.sub) {
      io.result := io.a - io.b
    }
    is(FUSel.mul) {
      io.result := io.a * io.b
    }
    is(FUSel.shl) {
      io.result := (io.a.asUInt << truncatedB).asTypeOf(io.result)
    }
    is(FUSel.lshr) {
      io.result := (io.a.asUInt >> truncatedB).asTypeOf(io.a).asTypeOf(io.result)
    }
    is(FUSel.ashr) {
      io.result := (io.a.asUInt.asSInt >> truncatedB).asTypeOf(io.a).asTypeOf(io.result)
    }
    is(FUSel.and) {
      io.result := (io.a.asUInt & io.b.asUInt).asTypeOf(io.result)
    }
    is(FUSel.or) {
      io.result := (io.a.asUInt | io.b.asUInt).asTypeOf(io.result)
    }
    is(FUSel.xor) {
      io.result := (io.a.asUInt ^ io.b.asUInt).asTypeOf(io.result)
    }
  }

}
