package bitstreamassembler

object toCArray {
  def apply(data: Array[Byte], alignment: Int, name: String): String = {
    val hex = data.map(b => f"0x$b%02x").grouped(16).map("  " ++ _.mkString(", ")).mkString(",\n")
    s"unsigned char $name[] __attribute__ ((aligned($alignment))) = {\n$hex\n};"
  }
}

