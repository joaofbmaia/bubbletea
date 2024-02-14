package bubbletea

import chisel3._
import chisel3.util._

class MeshData[T <: Data](params: BubbleteaParams[T]) extends Bundle {
  val north = Vec(params.meshColumns, params.dataType)
  val south = Vec(params.meshColumns, params.dataType)
  val west = Vec(params.meshRows, params.dataType)
  val east = Vec(params.meshRows, params.dataType)
}

class Mesh[T <: Data: Arithmetic](params: BubbleteaParams[T]) extends Module {
  val io = IO(new Bundle {
    val fire = Input(Bool())
    val currentModuloCycle = Input(UInt(log2Ceil(params.maxInitiationInterval).W))

    val in = Input(new MeshData(params))
    val out = Output(new MeshData(params))

    val configuration = Input(Vec(params.maxInitiationInterval, Vec(params.meshRows, Vec(params.meshColumns, new ProcessingElementConfigBundle(params)))))
  })

  val pe = Seq.fill(params.meshRows, params.meshColumns)(Module(new ProcessingElement(params)))
  val currentCycleConfiguration = Wire(Vec(params.meshRows, Vec(params.meshColumns, new ProcessingElementConfigBundle(params))))
  currentCycleConfiguration := io.configuration(io.currentModuloCycle)

  // Connect the Processing Elements
  for (row <- 0 until params.meshRows) {
    for (col <- 0 until params.meshColumns) {
      pe(row)(col).io.fire := io.fire
      pe(row)(col).io.configuration := currentCycleConfiguration(row)(col)
      if (row > 0) pe(row)(col).io.in.north := pe(row - 1)(col).io.out.south
      if (row < params.meshRows - 1) pe(row)(col).io.in.south := pe(row + 1)(col).io.out.north
      if (col > 0) pe(row)(col).io.in.west := pe(row)(col - 1).io.out.east
      if (col < params.meshColumns - 1) pe(row)(col).io.in.east := pe(row)(col + 1).io.out.west
    }
  }

  // Connect the Mesh inputs to the outside world
  for (row <- 0 until params.meshRows) {
    for (col <- 0 until params.meshColumns) {
      if (row == 0) pe(row)(col).io.in.north := io.in.north(col)
      if (row == params.meshRows - 1) pe(row)(col).io.in.south := io.in.south(col)
      if (col == 0) pe(row)(col).io.in.west := io.in.west(row)
      if (col == params.meshColumns - 1) pe(row)(col).io.in.east := io.in.east(row)
    }
  }

  // Connect the Mesh outputs to the outside world
  for (row <- 0 until params.meshRows) {
    for (col <- 0 until params.meshColumns) {
      if (row == 0) io.out.north(col) := pe(row)(col).io.out.north
      if (row == params.meshRows - 1) io.out.south(col) := pe(row)(col).io.out.south
      if (col == 0) io.out.west(row) := pe(row)(col).io.out.west
      if (col == params.meshColumns - 1) io.out.east(row) := pe(row)(col).io.out.east
    }
  }

}
