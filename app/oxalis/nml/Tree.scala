package oxalis.nml

import com.scalableminds.util.geometry.{Point3D, Vector3D}
import com.scalableminds.util.image.Color
import play.api.libs.json.Json

case class Tree(treeId: Int, nodes: Set[Node], edges: Set[Edge], color: Option[Color], name: String = "") extends TreeLike {

  def addNodes(ns: Set[Node]) = this.copy(nodes = nodes ++ ns)

  def removeNode(nid: Int) = this.copy(nodes = nodes.filter(_.id != nid))

  def updateNode(node: Node) = this.copy(nodes = nodes.filter(_.id != node.id) + node)

  def addEdges(es: Set[Edge]) = this.copy(edges = edges ++ es)

  def removeEdge(edge: Edge) = this.copy(edges = edges - edge - edge.inverted)

  def timestamp =
    if (nodes.isEmpty)
      System.currentTimeMillis()
    else
      nodes.minBy(_.timestamp).timestamp

  def containsNode(nid: Int) =
    nodes.exists(_.id == nid)

  def containsEdge(s: Edge) =
    edges.exists(e =>
      e.source == s.source && e.target == s.target ||
        e.source == s.target && e.target == s.source)

  def --(t: Tree) = {
    this.copy(nodes = nodes -- t.nodes, edges = edges -- t.edges)
  }

  def ++(t: Tree) = {
    this.copy(nodes = nodes ++ t.nodes, edges = edges ++ t.edges)
  }

  def changeTreeId(updatedTreeId: Int) = {
    this.copy(treeId = updatedTreeId)
  }

  def changeName(newName: String) = {
    this.copy(name = newName)
  }

  def applyNodeMapping(f: Int => Int) = {
    this.copy(
      nodes = nodes.map(node => node.copy(id = f(node.id))),
      edges = edges.map(edge => edge.copy(source = f(edge.source), target = f(edge.target))))
  }

  def addNamePrefix(prefix: String) = {
    this.copy(name = prefix + name)
  }
}

object Tree {
  implicit val treeFormat = Json.format[Tree]

  def empty = Tree(1, Set.empty, Set.empty, None)

  def createFrom(node: Point3D, rotation: Vector3D) =
    Tree(1, Set(Node(1, node, rotation)), Set.empty, Some(Color.RED))
}
