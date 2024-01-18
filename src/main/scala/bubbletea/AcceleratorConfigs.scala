package bubbletea

import chisel3._
import chisel3.util.log2Ceil

case class AcceleratorConfig[T <: Data](
  dataType:    T,
  meshRows:    Int,
  meshColumns: Int,
  maxInitiationInterval: Int,
  maxMacroStreams: Int
) {
  val maxMicroStreams: Int = (2 * meshRows + 2 * meshColumns) * maxInitiationInterval
  val macroStreamDepth: Int = maxMicroStreams / maxMacroStreams
  val numberOfRemaperElements: Int = maxMacroStreams * macroStreamDepth
  val numberOfRempaerSwitchStages: Int = 2 * log2Ceil(numberOfRemaperElements) - 1
  val numberOfRemaperSwitchesPerStage: Int = numberOfRemaperElements / 2

  override def toString: String = 
    s"AcceleratorConfig(\n  dataType = $dataType,\n  meshRows = $meshRows,\n  meshColumns = $meshColumns,\n  maxInitiationInterval = $maxInitiationInterval,\n  maxMacroStreams = $maxMacroStreams,\n  maxMicroStreams = $maxMicroStreams,\n  macroStreamDepth = $macroStreamDepth\n)"
}

object CommonAcceleratorConfigs {
  val defaultConfig = AcceleratorConfig[SInt](
    dataType = SInt(32.W),
    meshRows = 4,
    meshColumns = 4,
    maxInitiationInterval = 4,
    maxMacroStreams = 4
  )

  val minimalConfig = AcceleratorConfig[UInt](
    dataType = UInt(8.W),
    meshRows = 2,
    meshColumns = 2,
    maxInitiationInterval = 2,
    maxMacroStreams = 2
  )
}
