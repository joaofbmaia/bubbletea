package bitstreamassembler

import upickle.default._

sealed trait MacroDescriptionElement

case class VectorizeInstruction(vectorize: Boolean) extends MacroDescriptionElement

case class DimensionDescriptor(
  offset: Long,
  size:   Int,
  stride: Int)
    extends MacroDescriptionElement

case class ModifierDescriptor(
  target:       String,
  behavior:     String,
  displacement: Int,
  size:         Int)
    extends MacroDescriptionElement

case class MacroStream(
  macro_description: Seq[MacroDescriptionElement],
  micro_pattern:     Seq[Option[Int]])

case class StreamsData(
  loads:  Seq[MacroStream],
  stores: Seq[MacroStream])

object StreamsData {
  implicit val macroDescriptionElementRw: ReadWriter[MacroDescriptionElement] =
    readwriter[ujson.Value].bimap[MacroDescriptionElement](
      // Serialization
      {
        case VectorizeInstruction(vectorize) => ujson.Obj("vectorize" -> vectorize)
        case DimensionDescriptor(offset, size, stride) =>
          ujson.Obj("offset" -> offset.toString, "size" -> size, "stride" -> stride)
        case ModifierDescriptor(target, behavior, displacement, size) =>
          ujson.Obj("target" -> target, "behavior" -> behavior, "displacement" -> displacement, "size" -> size)
      },
      // Deserialization
      json => {
        val obj = json.obj
        if (obj.contains("vectorize")) {
          obj.get("vectorize") match {
            case Some(ujson.Bool(vectorize)) => VectorizeInstruction(vectorize)
            case _                           => throw new Exception("Invalid format for VectorizeInstruction")
          }
        } else if (obj.contains("offset")) {
          (obj.get("offset"), obj.get("size"), obj.get("stride")) match {
            case (Some(ujson.Str(offsetStr)), Some(ujson.Num(size)), Some(ujson.Num(stride))) =>
              val offset =
                if (offsetStr.startsWith("0x")) java.lang.Long.parseLong(offsetStr.drop(2), 16) else offsetStr.toLong
              DimensionDescriptor(offset, size.toInt, stride.toInt)
            case _ => throw new Exception("Invalid format for DimensionDescriptor")
          }
        } else if (obj.contains("target")) {
          (obj.get("target"), obj.get("behavior"), obj.get("displacement"), obj.get("size")) match {
            case (
                  Some(ujson.Str(target)),
                  Some(ujson.Str(behavior)),
                  Some(ujson.Num(displacement)),
                  Some(ujson.Num(size))
                ) if Set("SIZE", "STRIDE", "OFFSET").contains(target) && Set("INC", "DEC").contains(behavior) =>
              ModifierDescriptor(target, behavior, displacement.toInt, size.toInt)
            case _ => throw new Exception("Invalid format for ModifierDescriptor")
          }
        } else throw new Exception("Unknown object type")
      }
    )

  implicit val macroStreamRw: ReadWriter[MacroStream] = macroRW
  implicit val streamsDataRw: ReadWriter[StreamsData] = macroRW

  implicit val optionIntRw: ReadWriter[Option[Int]] = readwriter[ujson.Value].bimap[Option[Int]](
    // Serialization
    {
      case Some(value) => ujson.Num(value)
      case None        => ujson.Null
    },
    // Deserialization
    {
      case json if json.isNull => None
      case ujson.Num(value)    => Some(value.toInt)
      case _                   => throw new Exception("Invalid JSON value for Option[Int]")
    }
  )
}

object TestApp extends App {
  // Create a StreamsData object
  val data = StreamsData(
    loads = Seq(
      MacroStream(
        macro_description = Seq(
          VectorizeInstruction(vectorize = true),
          DimensionDescriptor(offset = 0x81000000L, size = 2, stride = 3),
          ModifierDescriptor(target = "SIZE", behavior = "INC", displacement = 4, size = 5)
        ),
        micro_pattern = Seq(Some(1), None, Some(2))
      )
    ),
    stores = Seq.empty
  )

  // Serialize the StreamsData object to a JSON string
  val json = write(data)

  // Print the JSON string
  println(json)

  // Deserialize the JSON string back to a StreamsData object
  val data2 = read[StreamsData](json)

  // Print the deserialized StreamsData object
  println(data2)

  // Compare the original and deserialized StreamsData objects
  println(data == data2)

  // val json2 = "{\"loads\":[{\"macro_description\":[{\"offset\":\"0x81000000\",\"size\":2,\"stride\":1},{\"vectorize\":true},{\"offset\":\"0\",\"size\":512,\"stride\":2},{\"offset\":\"0\",\"size\":1024,\"stride\":1024}],\"micro_pattern\":[19,11]},{\"macro_description\":[{\"offset\":\"0x82000000\",\"size\":2,\"stride\":1},{\"vectorize\":true},{\"offset\":\"0\",\"size\":512,\"stride\":2},{\"offset\":\"0\",\"size\":1024,\"stride\":1024}],\"micro_pattern\":[16,6]},{\"macro_description\":[{\"offset\":\"0x83000000\",\"size\":1,\"stride\":0},{\"vectorize\":true},{\"offset\":\"0\",\"size\":512,\"stride\":0},{\"offset\":\"0\",\"size\":1024,\"stride\":1}],\"micro_pattern\":[13]}],\"stores\":[{\"macro_description\":[{\"offset\":\"0x83000000\",\"size\":1,\"stride\":0},{\"vectorize\":true},{\"offset\":\"0\",\"size\":512,\"stride\":0},{\"offset\":\"0\",\"size\":1024,\"stride\":1}],\"micro_pattern\":[9]}]}"
  // val data3 = read[StreamsData](json2)
  // println(data3)
}
