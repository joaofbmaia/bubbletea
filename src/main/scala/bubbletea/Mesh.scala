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

  val mesh = Seq.fill(config.meshRows, config.meshColumns)(Module(new ProcessingElement(config)))

  // Connect the Processing Elements
  for (row <- 0 until config.meshRows) {
    for (col <- 0 until config.meshColumns) {
      mesh(row)(col).io.fire := io.fire
      mesh(row)(col).io.configuration := io.configuration(row)(col)
      if (row > 0) mesh(row)(col).io.in.north := mesh(row - 1)(col).io.out.south
      if (row < config.meshRows - 1) mesh(row)(col).io.in.south := mesh(row + 1)(col).io.out.north
      if (col > 0) mesh(row)(col).io.in.west := mesh(row)(col - 1).io.out.east
      if (col < config.meshColumns - 1) mesh(row)(col).io.in.east := mesh(row)(col + 1).io.out.west
    }
  }

  // Connect the Mesh inputs to the outside world
  for (row <- 0 until config.meshRows) {
    for (col <- 0 until config.meshColumns) {
      if (row == 0) mesh(row)(col).io.in.north := io.in.north(col)
      if (row == config.meshRows - 1) mesh(row)(col).io.in.south := io.in.south(col)
      if (col == 0) mesh(row)(col).io.in.west := io.in.west(row)
      if (col == config.meshColumns - 1) mesh(row)(col).io.in.east := io.in.east(row)
    }
  }

  // Connect the Mesh outputs to the outside world
  for (row <- 0 until config.meshRows) {
    for (col <- 0 until config.meshColumns) {
      if (row == 0) io.out.north(col) := mesh(row)(col).io.out.north
      if (row == config.meshRows - 1) io.out.south(col) := mesh(row)(col).io.out.south
      if (col == 0) io.out.west(row) := mesh(row)(col).io.out.west
      if (col == config.meshColumns - 1) io.out.east(row) := mesh(row)(col).io.out.east
    }
  }

}
