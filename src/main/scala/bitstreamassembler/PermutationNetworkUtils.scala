package bitstreamassembler

import bubbletea._
import chisel3.util.isPow2
import chisel3.util.log2Ceil

object Side extends Enumeration {
  type Side = Value
  val North, South, West, East = Value
}

case class RemaperMask(used: Boolean, moduloCycle: Int, side: Side.Side, index: Int)

object SwitchSetting extends Enumeration {
  type SwitchSetting = Value
  val Straight, Cross = Value
}

object PermutationNetworkUtils {
  def generateSwitchSettings(permutationIn: Seq[Int], permutationOut: Seq[Int]): Seq[Seq[Boolean]] = {
    import scala.collection.mutable.ArrayBuffer
    import SwitchSetting._
    
    require(permutationIn.length == permutationOut.length, "Input and output permutations must have the same length")
    val n = permutationIn.length
    require(isPow2(n), "N must be a power of 2")
    require(n >= 4, "N must be greater than or equal to 4")

    def generateSwitchSettingsRec(permutationIn: Seq[Int], permutationOut: Seq[Int]): Seq[Seq[SwitchSetting]] = {
      val n = permutationIn.length

      def calculateSwitches(permutationIn: Seq[Int], permutationOut: Seq[Int]): (Seq[SwitchSetting], Seq[SwitchSetting]) = {
        val n = permutationIn.length
        val stages = 2 * log2Ceil(n) - 1
        val switchesPerStage = n / 2

        val entrySwitch = ArrayBuffer.fill(switchesPerStage)(Straight)
        val exitSwitch = ArrayBuffer.fill(switchesPerStage)(Straight)

        val entrySwitchSet = ArrayBuffer.fill(switchesPerStage)(false)

        // If every entry switch has not been set, repeat the process ignoring the entry switches that have been already set
        while(entrySwitchSet.reduce(_ && _) == false) {
          // Choose an arbitrary entry switch and set it straight
          var currentEntrySwitch = entrySwitchSet.indexOf(false)
          entrySwitch(currentEntrySwitch) = Straight
          entrySwitchSet(currentEntrySwitch) = true

          var done = false
          while(!done) {
            // For current entry switch, find the mate of the signal connected to the bottom subnetwork.
            val bottomSignal = if (entrySwitch(currentEntrySwitch) == Straight) permutationIn(2 * currentEntrySwitch + 1) else permutationIn(2 * currentEntrySwitch)
            val mateOfBottomSignal = permutationOut(permutationOut.indexOf(bottomSignal) ^ 1)

            // Wire it (the mate) to the top subnetwork.
            exitSwitch(permutationOut.indexOf(mateOfBottomSignal) / 2) = if (permutationOut.indexOf(mateOfBottomSignal) % 2 == 0) Straight else Cross
            
            // Choose the entry switch to which the mate is connected as the new current entry switch
            currentEntrySwitch = permutationIn.indexOf(mateOfBottomSignal) / 2

            // If the new current entry switch has already been set, terminate the loop
            if(entrySwitchSet(currentEntrySwitch)) {
              done = true
            }
            // (Continue) Wire it (the mate) to the top subnetwork.
            else {
              entrySwitch(currentEntrySwitch) = if (permutationIn.indexOf(mateOfBottomSignal) % 2 == 0) Straight else Cross
              entrySwitchSet(currentEntrySwitch) = true
            }
          }
        }

        (entrySwitch.toSeq, exitSwitch.toSeq)
      }
      
      def calculateSubnetworkPermutations(permutationIn: Seq[Int], permutationOut: Seq[Int], entrySwitches: Seq[SwitchSetting], exitSwitches: Seq[SwitchSetting]): ((Seq[Int], Seq[Int]), (Seq[Int], Seq[Int])) = {
        val n = permutationIn.length

        // Switch the signals according to the entry and exit switches
        val switchedPermutationIn: Seq[Int] = Seq.tabulate(n)(i => if (entrySwitches(i / 2) == Straight) permutationIn(i) else permutationIn(i ^ 1))
        val switchedPermutationOut: Seq[Int] = Seq.tabulate(n)(i => if (exitSwitches(i / 2) == Straight) permutationOut(i) else permutationOut(i ^ 1))

        // Split the switched permutation into two subnetworks according to the Benes network structure
        val topSubnetworkPermutationIn = switchedPermutationIn.zipWithIndex.filter(_._2 % 2 == 0).map(_._1)
        val topSubnetworkPermutationOut = switchedPermutationOut.zipWithIndex.filter(_._2 % 2 == 0).map(_._1)
        val bottomSubnetworkPermutationIn = switchedPermutationIn.zipWithIndex.filter(_._2 % 2 == 1).map(_._1)
        val bottomSubnetworkPermutationOut = switchedPermutationOut.zipWithIndex.filter(_._2 % 2 == 1).map(_._1)

        // Return the subnetwork permutation correspondences
        ((topSubnetworkPermutationIn, topSubnetworkPermutationOut), (bottomSubnetworkPermutationIn, bottomSubnetworkPermutationOut))
      }

      // Calculate the entry and exit switches
      val (entrySwitches, exitSwitches) = calculateSwitches(permutationIn, permutationOut)

      // Calculate the subnetwork permutation correspondences
      val ((topSubnetworkPermutationIn, topSubnetworkPermutationOut), 
           (bottomSubnetworkPermutationIn, bottomSubnetworkPermutationOut)) = 
            calculateSubnetworkPermutations(permutationIn, permutationOut, entrySwitches, exitSwitches)

      // Recursively calculate the switch settings for the subnetworks, if the subnetworks are not trivial (i.e. n = 4)
      val topSubnetworkSwitches: Seq[Seq[SwitchSetting]] = 
        if (n == 4) {if (topSubnetworkPermutationIn == topSubnetworkPermutationOut) Seq(Seq(Straight)) else Seq(Seq(Cross))}
        else generateSwitchSettingsRec(topSubnetworkPermutationIn, topSubnetworkPermutationOut)
      
      val bottomSubnetworkSwitches: Seq[Seq[SwitchSetting]] = 
        if (n == 4) {if (bottomSubnetworkPermutationIn == bottomSubnetworkPermutationOut) Seq(Seq(Straight)) else Seq(Seq(Cross))}
        else generateSwitchSettingsRec(bottomSubnetworkPermutationIn, bottomSubnetworkPermutationOut)

      // Concatenate the switch settings for the subnetworks and return
      val middleSwitches: Seq[Seq[SwitchSetting]] = (topSubnetworkSwitches.transpose ++ bottomSubnetworkSwitches.transpose).transpose
      val switches: Seq[Seq[SwitchSetting]] = Seq(entrySwitches) ++ middleSwitches ++ Seq(exitSwitches)
      switches
    }

    assert(permutationIn.toSet == permutationOut.toSet, "Input is not a permutation, i.e. it does not contain all the elements from 0 to N-1 exactly once")

    generateSwitchSettingsRec(permutationIn, permutationOut).map(_.map(_ == Cross))
  }

  def generateSwitchSettingsFromDstMask(dstMask: Seq[Seq[RemaperMask]], rows: Int, columns: Int): Seq[Seq[Boolean]] = {
    // dimenstions order: (moduloCycle, side (order: east, west, south, north), index)
    def dstMask2index(dstMask: RemaperMask): Int = {
      val rowSize = columns
      val columnSize = rows
      val singleCycleBufferSize = rowSize * 2 + columnSize * 2
      val ret = dstMask.moduloCycle * singleCycleBufferSize + 
                (if (dstMask.side == Side.East) 0 else 
                 if (dstMask.side == Side.West) columnSize else 
                 if (dstMask.side == Side.South) columnSize * 2 else 
                 columnSize * 2 + rowSize) + 
                 dstMask.index
      ret
    }
    
    val permutationsUnfull: Seq[Int] = dstMask.flatten.map(x => if (x.used) dstMask2index(x) else -1)

    val missingIndices = (0 until permutationsUnfull.length).filter(!permutationsUnfull.contains(_))
    val missingIndicesIterator = missingIndices.iterator

    val permutations = permutationsUnfull.map(x => if (x == -1) missingIndicesIterator.next() else x)

    generateSwitchSettings(permutations, (0 until permutations.length))
  }

  def generateSwitchSettingsFromSrcMask(srcMask: Seq[Seq[RemaperMask]], rows: Int, columns: Int): Seq[Seq[Boolean]] = {
    // dimenstions order: (moduloCycle, side (order: east, west, south, north), index)
    def srcMask2index(srcMask: RemaperMask): Int = {
      val rowSize = columns
      val columnSize = rows
      val singleCycleBufferSize = rowSize * 2 + columnSize * 2
      val ret = srcMask.moduloCycle * singleCycleBufferSize + 
                (if (srcMask.side == Side.East) 0 else 
                 if (srcMask.side == Side.West) columnSize else 
                 if (srcMask.side == Side.South) columnSize * 2 else 
                 columnSize * 2 + rowSize) + 
                 srcMask.index
      ret
    }
    
    val permutationsUnfull: Seq[Int] = srcMask.flatten.map(x => if (x.used) srcMask2index(x) else -1)

    val missingIndices = (0 until permutationsUnfull.length).filter(!permutationsUnfull.contains(_))
    val missingIndicesIterator = missingIndices.iterator

    val permutations = permutationsUnfull.map(x => if (x == -1) missingIndicesIterator.next() else x)

    generateSwitchSettings((0 until permutations.length), permutations)
  }
}