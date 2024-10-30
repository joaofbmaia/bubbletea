package bubbletea

import chisel3._
import chisel3.util.log2Ceil

trait BubbleteaParams[T <: Data] {
  val baseAddress : BigInt

  val dataType:    T
  val meshRows:    Int
  val meshColumns: Int
  val maxInitiationInterval: Int
  val maxSimultaneousLoadMacroStreams: Int
  val maxSimultaneousStoreMacroStreams : Int
  val maxConfigurationInstructions : Int

  val seUseSvBlackBox: Boolean
  val seHackStoreBursts: Boolean
  val seMaxStreamDims: Int
  val seMaxStreamMods: Int
  val seOffsetWidth: Int
  val seStrideWidth: Int
  val seSizeWidth: Int
  val seStreamIdWidth: Int
  val seLrqNumTables: Int
  val seLrqNumRequests: Int
  val seLlbNumTables: Int
  val seLmmuNumVecs: Int
  val seSmmuNumAddresses: Int
  val seMaxNumLoadStreams: Int
  val seMaxNumStoreStreams: Int

  val rfSize: Int
  val rfReadPorts: Int
  val rfWritePorts: Int

  val maxDelayIntervals: Int
  val maxStoreStreamFixedDelay: Int


  val maxLoadMicroStreams: Int = (2 * meshRows + 2 * meshColumns) * maxInitiationInterval
  // Number of elements in a macrostream buffer
  val macroStreamDepth: Int = maxLoadMicroStreams / maxSimultaneousLoadMacroStreams //TODO: this doesnt need to be like this, if the load remaper is connected in a way that supports that some of the elements on the macrostream side are unnconnected
  val numberOfLoadRemaperElements: Int = maxLoadMicroStreams
  val numberOfLoadRemaperSwitchStages: Int = 2 * log2Ceil(numberOfLoadRemaperElements) - 1
  val numberOfLoadRemaperSwitchesPerStage: Int = numberOfLoadRemaperElements / 2

  val maxStoreMicroStreams: Int = (2 * meshRows + 2 * meshColumns) * maxInitiationInterval
  val numberOfStoreRemaperElements: Int = maxStoreMicroStreams
  val numberOfStoreRemaperSwitchStages: Int = 2 * log2Ceil(numberOfStoreRemaperElements) - 1
  val numberOfStoreRemaperSwitchesPerStage: Int = numberOfStoreRemaperElements / 2

  // needs updating
  override def toString: String = 
    s"BubbleteaParams(\n  dataType = $dataType,\n  meshRows = $meshRows,\n  meshColumns = $meshColumns,\n  maxInitiationInterval = $maxInitiationInterval,\n  maxSimultaneousLoadMacroStreams = $maxSimultaneousLoadMacroStreams,\n  maxLoadMicroStreams = $maxLoadMicroStreams,\n  macroStreamDepth = $macroStreamDepth\n)"
}

case class UIntBubbleteaParams(
  baseAddress : BigInt,

  dataType:    UInt,
  meshRows:    Int,
  meshColumns: Int,
  maxInitiationInterval: Int,
  maxSimultaneousLoadMacroStreams: Int,
  maxSimultaneousStoreMacroStreams : Int,
  maxConfigurationInstructions : Int,

  seUseSvBlackBox: Boolean,
  seHackStoreBursts: Boolean,
  seMaxStreamDims: Int,
  seMaxStreamMods: Int,
  seOffsetWidth: Int,
  seStrideWidth: Int,
  seSizeWidth: Int,
  seStreamIdWidth: Int,
  seLrqNumTables: Int,
  seLrqNumRequests: Int,
  seLlbNumTables: Int,
  seLmmuNumVecs: Int,
  seSmmuNumAddresses: Int,
  seMaxNumLoadStreams: Int,
  seMaxNumStoreStreams: Int,

  rfSize: Int,
  rfReadPorts: Int,
  rfWritePorts: Int,

  maxDelayIntervals: Int,
  maxStoreStreamFixedDelay: Int
) extends BubbleteaParams[UInt]

case class SIntBubbleteaParams(
  baseAddress : BigInt,

  dataType:    SInt,
  meshRows:    Int,
  meshColumns: Int,
  maxInitiationInterval: Int,
  maxSimultaneousLoadMacroStreams: Int,
  maxSimultaneousStoreMacroStreams : Int,
  maxConfigurationInstructions : Int,

  seUseSvBlackBox: Boolean,
  seHackStoreBursts: Boolean,
  seMaxStreamDims: Int,
  seMaxStreamMods: Int,
  seOffsetWidth: Int,
  seStrideWidth: Int,
  seSizeWidth: Int,
  seStreamIdWidth: Int,
  seLrqNumTables: Int,
  seLrqNumRequests: Int,
  seLlbNumTables: Int,
  seLmmuNumVecs: Int,
  seSmmuNumAddresses: Int,
  seMaxNumLoadStreams: Int,
  seMaxNumStoreStreams: Int,

  rfSize: Int,
  rfReadPorts: Int,
  rfWritePorts: Int,

  maxDelayIntervals: Int,
  maxStoreStreamFixedDelay: Int
) extends BubbleteaParams[SInt]

case class FloatBubbleteaParams(
  baseAddress : BigInt,

  dataType:    Float,
  meshRows:    Int,
  meshColumns: Int,
  maxInitiationInterval: Int,
  maxSimultaneousLoadMacroStreams: Int,
  maxSimultaneousStoreMacroStreams : Int,
  maxConfigurationInstructions : Int,

  seUseSvBlackBox: Boolean,
  seHackStoreBursts: Boolean,
  seMaxStreamDims: Int,
  seMaxStreamMods: Int,
  seOffsetWidth: Int,
  seStrideWidth: Int,
  seSizeWidth: Int,
  seStreamIdWidth: Int,
  seLrqNumTables: Int,
  seLrqNumRequests: Int,
  seLlbNumTables: Int,
  seLmmuNumVecs: Int,
  seSmmuNumAddresses: Int,
  seMaxNumLoadStreams: Int,
  seMaxNumStoreStreams: Int,

  rfSize: Int,
  rfReadPorts: Int,
  rfWritePorts: Int,

  maxDelayIntervals: Int,
  maxStoreStreamFixedDelay: Int
) extends BubbleteaParams[Float]

object CommonBubbleteaParams {
  val float4x4 = FloatBubbleteaParams(
    baseAddress = 0x10080000,
    dataType = Float(8, 24),
    meshRows = 4,
    meshColumns = 4,
    maxInitiationInterval = 8,
    maxSimultaneousLoadMacroStreams = 4,
    maxSimultaneousStoreMacroStreams = 4, // right now this must be the same as maxSimultaneousLoadMacroStreams
    maxConfigurationInstructions = 32,
    seUseSvBlackBox = false,
    seHackStoreBursts = true,
    seMaxStreamDims = 6,
    seMaxStreamMods = 3,
    seOffsetWidth = 33,
    seStrideWidth = 32,
    seSizeWidth = 32,
    seStreamIdWidth = 32,
    seLrqNumTables = 32,
    seLrqNumRequests = 16,
    seLlbNumTables = 4,
    seLmmuNumVecs = 4,
    seSmmuNumAddresses = 32,
    seMaxNumLoadStreams = 4,
    seMaxNumStoreStreams = 4,
    rfSize = 4,
    rfReadPorts = 2,
    rfWritePorts = 2,
    maxDelayIntervals = 2,
    maxStoreStreamFixedDelay = 32
  )
}