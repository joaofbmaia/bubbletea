package bubbletea

import chisel3._
import chisel3.util.isPow2
import chisel3.util.log2Ceil

trait BenesInterface[T <: Data] extends Module {
  val io: Bundle { val in: Vec[T]; val out: Vec[T]; val select: Vec[Vec[Bool]] }
}

class Crossbar2x2[T <: Data](dataType: T) extends Module with BenesInterface[T] {
  val io = IO(new Bundle {
    val in = Input(Vec(2, dataType))
    val out = Output(Vec(2, dataType))
    val select = Input(Vec(1, Vec(1, Bool())))
  })
  io.out(0) := Mux(io.select(0)(0), io.in(1), io.in(0))
  io.out(1) := Mux(io.select(0)(0), io.in(0), io.in(1))
}

class BenesPermutationNetwork[T <: Data](dataType: T, N: Int) extends Module with BenesInterface[T] {
  require(isPow2(N), "N must be a power of 2")
  require(N >= 4, "N must be greater than or equal to 4")
  val stages = 2 * log2Ceil(N) - 1
  val switchesPerStage = N / 2
  val io = IO(new Bundle {
    val in = Input(Vec(N, dataType))
    val out = Output(Vec(N, dataType))
    val select = Input(Vec(stages, Vec(switchesPerStage, Bool())))
  })

  val entrySwitches = Seq.fill(switchesPerStage)(Module(new Crossbar2x2(dataType)))
  val exitSwitches = Seq.fill(switchesPerStage)(Module(new Crossbar2x2(dataType)))

  val topSubnetwork =
    if (N == 4) Module(new Crossbar2x2(dataType)) else Module(new BenesPermutationNetwork(dataType, N / 2))
  val bottomSubnetwork =
    if (N == 4) Module(new Crossbar2x2(dataType)) else Module(new BenesPermutationNetwork(dataType, N / 2))

  for (i <- 0 until switchesPerStage) {
    entrySwitches(i).io.in(0) := io.in(2 * i)
    entrySwitches(i).io.in(1) := io.in(2 * i + 1)

    io.out(2 * i) := exitSwitches(i).io.out(0)
    io.out(2 * i + 1) := exitSwitches(i).io.out(1)

    topSubnetwork.io.in(i) := entrySwitches(i).io.out(0)
    bottomSubnetwork.io.in(i) := entrySwitches(i).io.out(1)

    exitSwitches(i).io.in(0) := topSubnetwork.io.out(i)
    exitSwitches(i).io.in(1) := bottomSubnetwork.io.out(i)
  }

  for (i <- 0 until switchesPerStage) {
    entrySwitches(i).io.select(0)(0) := (if(i == 0) false.B else io.select(0)(i))
    exitSwitches(i).io.select(0)(0) := io.select(stages - 1)(i)

    // topSubnetwork.io.select := VecInit(io.select.slice(1, stages - 1).map(_.slice(0, switchesPerStage / 2)).map(VecInit(_)))
    // bottomSubnetwork.io.select := VecInit(io.select.slice(1, stages - 1).map(_.slice(switchesPerStage / 2, switchesPerStage)).map(VecInit(_)))
  }

  def vecSlice[T <: Data](vec: Vec[T], start: Int, end: Int): Vec[T] = {
    VecInit(vec.slice(start, end))
  }

  def vec2dSlice[T <: Data](vec: Vec[Vec[T]], xStart: Int, xEnd: Int, yStart: Int, yEnd: Int): Vec[Vec[T]] = {
    VecInit(vec.slice(xStart, xEnd).map(vecSlice(_, yStart, yEnd)))
  }

  // topSubnetwork.io.select := VecInit(
  //   io.select.slice(1, stages - 1).map(_.slice(0, switchesPerStage / 2)).map(VecInit(_))
  // )
  
  // bottomSubnetwork.io.select := VecInit(
  //   io.select.slice(1, stages - 1).map(_.slice(switchesPerStage / 2, switchesPerStage)).map(VecInit(_))
  // )

  topSubnetwork.io.select := vec2dSlice(io.select, 1, stages - 1, 0, switchesPerStage / 2)
  bottomSubnetwork.io.select := vec2dSlice(io.select, 1, stages - 1, switchesPerStage / 2, switchesPerStage)

}

// import scala.collection.mutable.ArrayBuffer

// // case class CrossbarSwitch(setting: Option[Boolean])

// // type BenesNetwork = Either[BenesNetwork, CrossbarSwitch]

// class BenesNetwork(val permutationIn: Seq[Int], val permutationOut: Seq[Int]) {
//     type CrossbarSwitch = Option[Boolean]

//     assert(permutationIn.length == permutationOut.length)
//     val n = permutationIn.length
//     assert(n > 4 && (n & (n - 1)) == 0) // assert if n is a power of 2 and greater than 4

//     val entrySwitchesTemp: ArrayBuffer[CrossbarSwitch] = ArrayBuffer.fill(n/2)(None)
//     val exitSwitchesTemp: ArrayBuffer[CrossbarSwitch] = ArrayBuffer.fill(n/2)(None)
//     val topSubnetworkPermutationIn: ArrayBuffer[Int] = ArrayBuffer.fill(n/2)(0)
//     val topSubnetworkPermutationOut: ArrayBuffer[Int] = ArrayBuffer.fill(n/2)(0)
//     val bottomSubnetworkPermutationIn: ArrayBuffer[Int] = ArrayBuffer.fill(n/2)(0)
//     val bottomSubnetworkPermutationOut: ArrayBuffer[Int] = ArrayBuffer.fill(n/2)(0)

//     for (i <- 0 until n/2) {
//         if (entrySwitchesTemp(i).isDefined) {

//         }
//         else {
//             entrySwitchesTemp(i) = Some(false)
//             //val topSignal = permutationIn(2 * i)

//             val bottomSignal = if (entrySwitchesTemp(i).get) permutationIn(2 * i + 1) else permutationIn(2 * i)

//             val exitSwitchIndex = permutationOut.indexOf(bottomSignal) / 2

//             exitSwitchesTemp(exitSwitchIndex) = if(permutationOut.indexOf(bottomSignal) % 2 == 0) Some(true) else Some(false)

//             val mateIndex = if(permutationOut.indexOf(bottomSignal) % 2 == 0) permutationOut.indexOf(bottomSignal) + 1 else permutationOut.indexOf(bottomSignal) - 1
//             val mate = permutationOut(mateIndex)

//             val currentEntrySwitchIndex = permutationIn.indexOf(mate) / 2

//             //topSubnetworkPermutationIn(i) = topSignal
//             //bottomSubnetworkPermutationIn(i) = bottomSignal

//             //val bottomSingnalOutIndex = permutationOut.indexOf(bottomSignal)
//             //bottomSubnetworkPermutationOut(i) = bottomSingnalOutIndex / 2

//         }

//     }

// val entrySwitches : Seq[CrossbarSwitch] = Seq.fill(n/2)(CrossbarSwitch(Some(true))
// val exitSwitches : Seq[CrossbarSwitch] = Seq.fill(n/2)(CrossbarSwitch(true))
//     val topSubnetwork : Either[BenesNetwork, CrossbarSwitch] = if (n == 4) Right(CrossbarSwitch(true)) else Left(new BenesNetwork(n-1))
//     val bottomSubnetwork = if (n == 4) Right(Some(true)) else Left(new BenesNetwork(n-1))

// }
