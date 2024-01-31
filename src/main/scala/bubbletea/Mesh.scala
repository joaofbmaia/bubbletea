package bubbletea

import chisel3._

class MeshData[T <: Data](config: AcceleratorConfig[T]) extends Bundle {
  val north = Vec(config.meshColumns, config.dataType)
  val south = Vec(config.meshColumns, config.dataType)
  val west = Vec(config.meshRows, config.dataType)
  val east = Vec(config.meshRows, config.dataType)
}

class Mesh[T <: Data: Arithmetic](config: AcceleratorConfig[T]) extends Module {
  val io = IO(new Bundle {
    val fire = Input(Bool())

    val in = Input(new MeshData(config))
    val out = Output(new MeshData(config))

    val configuration = Input(Vec(config.meshRows, Vec(config.meshColumns, new ProcessingElementConfigBundle(config))))
  })

  val pe = Seq.fill(config.meshRows, config.meshColumns)(Module(new ProcessingElement(config)))

  // Connect the Processing Elements
  for (row <- 0 until config.meshRows) {
    for (col <- 0 until config.meshColumns) {
      pe(row)(col).io.fire := io.fire
      pe(row)(col).io.configuration := io.configuration(row)(col)
      if (row > 0) pe(row)(col).io.in.north := pe(row - 1)(col).io.out.south
      if (row < config.meshRows - 1) pe(row)(col).io.in.south := pe(row + 1)(col).io.out.north
      if (col > 0) pe(row)(col).io.in.west := pe(row)(col - 1).io.out.east
      if (col < config.meshColumns - 1) pe(row)(col).io.in.east := pe(row)(col + 1).io.out.west
    }
  }

  // Connect the Mesh inputs to the outside world
  for (row <- 0 until config.meshRows) {
    for (col <- 0 until config.meshColumns) {
      if (row == 0) pe(row)(col).io.in.north := io.in.north(col)
      if (row == config.meshRows - 1) pe(row)(col).io.in.south := io.in.south(col)
      if (col == 0) pe(row)(col).io.in.west := io.in.west(row)
      if (col == config.meshColumns - 1) pe(row)(col).io.in.east := io.in.east(row)
    }
  }

  // Connect the Mesh outputs to the outside world
  for (row <- 0 until config.meshRows) {
    for (col <- 0 until config.meshColumns) {
      if (row == 0) io.out.north(col) := pe(row)(col).io.out.north
      if (row == config.meshRows - 1) io.out.south(col) := pe(row)(col).io.out.south
      if (col == 0) io.out.west(row) := pe(row)(col).io.out.west
      if (col == config.meshColumns - 1) io.out.east(row) := pe(row)(col).io.out.east
    }
  }

}
