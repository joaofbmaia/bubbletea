package bubbletea

import chisel3._

/* This is a utility function to calculate the padding needed to add to a bundle so that the next field is aligned to a certain number of bits
Example usage (align to bytes):
class MyBundle extends Bundle {
    val a = UInt(13.W),
    val padding = UInt(calcAlignPadding(13, 8).W), // Add padding to align the next field to 1 bytes (8 bits)
    // in this case the padding will be 3 bits wide
    val b = UInt(8.W)
} */
object calcAlignPadding {
  def apply(currentWidth: Int, align: Int): Int = (align - currentWidth % align) % align
}

object rotateLeft {
    def apply(value: UInt, n: Int): UInt =
        if (n == 0) value
        else if (n < 0) rotateRight(value, -n)
        else value.tail(n) ## value.head(n)
}

object rotateRight {
    def apply(value: UInt, n: Int): UInt =
        if (n <= 0) rotateLeft(value, -n)
        else (value(n-1, 0)  ## (value >> n))
}
