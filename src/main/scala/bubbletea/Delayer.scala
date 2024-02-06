package bubbletea

import chisel3._
import chisel3.util.log2Ceil

class DelayerBundle[T <: Data](params: BubbleteaParams[T]) extends Bundle{
  val north = Vec(params.meshColumns, UInt(log2Ceil(params.maxMeshLatency + 1).W))
  val south = Vec(params.meshColumns, UInt(log2Ceil(params.maxMeshLatency + 1).W))
  val west = Vec(params.meshRows, UInt(log2Ceil(params.maxMeshLatency + 1).W))
  val east = Vec(params.meshRows, UInt(log2Ceil(params.maxMeshLatency + 1).W))
}

class DelayerConfigBundle[T <: Data](params: BubbleteaParams[T]) extends Bundle{
  val loads = new DelayerBundle(params)
  val stores = new DelayerBundle(params)
}

class Delayer[T <: Data: Arithmetic](params: BubbleteaParams[T]) extends Module {
  val io = IO(new Bundle {
    val fire = Input(Bool())

    val loadsIn = Input(new MeshData(params))
    val meshLoadsOut = Output(new MeshData(params))
    val meshStoresIn = Input(new MeshData(params))
    val storesOut = Output(new MeshData(params))

    val configuration = Input(new DelayerConfigBundle(params))
  })

  val loadDelaysNorth = Seq.fill(params.meshColumns)(Module(new VariablePipe(params.dataType, params.maxMeshLatency)))
  val loadDelaysSouth = Seq.fill(params.meshColumns)(Module(new VariablePipe(params.dataType, params.maxMeshLatency)))
  val loadDelaysWest = Seq.fill(params.meshRows)(Module(new VariablePipe(params.dataType, params.maxMeshLatency)))
  val loadDelaysEast = Seq.fill(params.meshRows)(Module(new VariablePipe(params.dataType, params.maxMeshLatency)))

  val storeDelaysNorth = Seq.fill(params.meshColumns)(Module(new VariablePipe(params.dataType, params.maxMeshLatency)))
  val storeDelaysSouth = Seq.fill(params.meshColumns)(Module(new VariablePipe(params.dataType, params.maxMeshLatency)))
  val storeDelaysWest = Seq.fill(params.meshRows)(Module(new VariablePipe(params.dataType, params.maxMeshLatency)))
  val storeDelaysEast = Seq.fill(params.meshRows)(Module(new VariablePipe(params.dataType, params.maxMeshLatency)))
  
  loadDelaysNorth.zipWithIndex.foreach { case (delay, i) =>
    delay.io.valid := io.fire
    delay.io.in := io.loadsIn.north(i)
    delay.io.latency := io.configuration.loads.north(i)
    io.meshLoadsOut.north(i) := delay.io.out
  }

  loadDelaysSouth.zipWithIndex.foreach { case (delay, i) =>
    delay.io.valid := io.fire
    delay.io.in := io.loadsIn.south(i)
    delay.io.latency := io.configuration.loads.south(i)
    io.meshLoadsOut.south(i) := delay.io.out
  }

  loadDelaysWest.zipWithIndex.foreach { case (delay, i) =>
    delay.io.valid := io.fire
    delay.io.in := io.loadsIn.west(i)
    delay.io.latency := io.configuration.loads.west(i)
    io.meshLoadsOut.west(i) := delay.io.out
  }

  loadDelaysEast.zipWithIndex.foreach { case (delay, i) =>
    delay.io.valid := io.fire
    delay.io.in := io.loadsIn.east(i)
    delay.io.latency := io.configuration.loads.east(i)
    io.meshLoadsOut.east(i) := delay.io.out
  }

  storeDelaysNorth.zipWithIndex.foreach { case (delay, i) =>
    delay.io.valid := io.fire
    delay.io.in := io.meshStoresIn.north(i)
    delay.io.latency := io.configuration.stores.north(i)
    io.storesOut.north(i) := delay.io.out
  }

  storeDelaysSouth.zipWithIndex.foreach { case (delay, i) =>
    delay.io.valid := io.fire
    delay.io.in := io.meshStoresIn.south(i)
    delay.io.latency := io.configuration.stores.south(i)
    io.storesOut.south(i) := delay.io.out
  }

  storeDelaysWest.zipWithIndex.foreach { case (delay, i) =>
    delay.io.valid := io.fire
    delay.io.in := io.meshStoresIn.west(i)
    delay.io.latency := io.configuration.stores.west(i)
    io.storesOut.west(i) := delay.io.out
  }

  storeDelaysEast.zipWithIndex.foreach { case (delay, i) =>
    delay.io.valid := io.fire
    delay.io.in := io.meshStoresIn.east(i)
    delay.io.latency := io.configuration.stores.east(i)
    io.storesOut.east(i) := delay.io.out
  }
}