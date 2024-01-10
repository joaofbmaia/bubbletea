package bubbletea

import chisel3._
import upickle.default

case class AcceleratorConfig[T <: Data](
  dataType:    T,
  meshRows:    Int,
  meshColumns: Int,
  maxInitiationInterval: Int,
  maxMacroStreams: Int) {}

object CommonAcceleratorConfigs {
  val defaultConfig = AcceleratorConfig[SInt](
    dataType = SInt(32.W),
    meshRows = 4,
    meshColumns = 4,
    maxInitiationInterval = 4,
    maxMacroStreams = 4
  )
}
