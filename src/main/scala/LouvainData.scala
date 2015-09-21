import com.esotericsoftware.kryo.io.{Input, Output}
import com.esotericsoftware.kryo.serializers.DefaultArraySerializers.ObjectArraySerializer
import com.esotericsoftware.kryo.{Kryo, KryoSerializable}

class LouvainData(var community: Long,
  var communitySigmaTot: Long,
  var internalWeight: Long,
  var nodeWeight: Long,
  var changed: Boolean) extends Serializable with KryoSerializable {

  def this() = this(-1L, 0L, 0L, 0L, false)

  override def toString: String =
    s"{community:$community,communitySigmaTot:$communitySigmaTot,internalWeight:$internalWeight,nodeWeight:$nodeWeight}"

  override def write(kryo: Kryo, output: Output): Unit = {
    kryo.writeObject(output, this.community)
    kryo.writeObject(output, this.communitySigmaTot)
    kryo.writeObject(output, this.internalWeight)
    kryo.writeObject(output, this.nodeWeight)
    kryo.writeObject(output, this.changed)
  }

  override def read(kryo: Kryo, input: Input): Unit = {
    this.community = kryo.readObject(input, classOf[Long])
    this.communitySigmaTot = kryo.readObject(input, classOf[Long])
    this.internalWeight = kryo.readObject(input, classOf[Long])
    this.nodeWeight = kryo.readObject(input, classOf[Long])
    this.changed = kryo.readObject(input, classOf[Boolean])
  }
}
