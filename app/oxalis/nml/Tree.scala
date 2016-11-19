package oxalis.nml

import com.scalableminds.util.geometry.{Point3D, Vector3D}
import com.scalableminds.util.image.Color
import play.api.libs.json.Json

case class Tree(
  id: Int,
  nodes: Set[Node] = Set.empty,
  edges: Set[Edge] = Set.empty,
  color: Option[Color] = None,
  branchPoints: List[BranchPoint] = Nil,
  comments: List[Comment] = Nil,
  name: String = "") extends TreeLike {

  def addNodes(ns: Set[Node]) = this.copy(nodes = nodes ++ ns)

  def removeNode(nid: Int) = this.copy(
    nodes = nodes.filter(_.id != nid),
    branchPoints = branchPoints.filter(_.id != nid),
    comments = comments.filter(_.node != nid))

  def moveTreeComponent(nodeIds: Set[Int], other: Tree) = {
    val (targetNodes, sourceNodes) = nodes.partition(n => nodeIds.contains(n.id))
    val (targetEdges, sourceEdges) = edges.partition(e => nodeIds.contains(e.source) && nodeIds.contains(e.target))
    val (targetBP, sourceBP) = branchPoints.partition(b => nodeIds.contains(b.id))
    val (targetC, sourceC) = comments.partition(c => nodeIds.contains(c.node))
    val filteredSourceEdges = sourceEdges.filterNot(e => nodeIds.contains(e.source) || nodeIds.contains(e.target))
    val updatedTargetTree = other
                            .addNodes(targetNodes)
                            .addEdges(targetEdges)
                            .copy(branchPoints = other.branchPoints ::: targetBP, comments = other.comments ::: targetC)
    val updatedSourceTree = this.copy(
      nodes = sourceNodes,
      edges = filteredSourceEdges,
      branchPoints = sourceBP,
      comments = sourceC)

    (updatedSourceTree, updatedTargetTree)
  }

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
    this.copy(id = updatedTreeId)
  }

  def changeName(newName: String) = {
    this.copy(name = newName)
  }

  def applyNodeMapping(f: Int => Int) = {
    this.copy(
      nodes = nodes.map(node => node.copy(id = f(node.id))),
      edges = edges.map(edge => edge.copy(source = f(edge.source), target = f(edge.target))),
      comments = comments.map(comment => comment.copy(node = f(comment.node))),
      branchPoints = branchPoints.map(bp => bp.copy(id = f(bp.id)))
    )
  }

  def addNamePrefix(prefix: String) = {
    this.copy(name = prefix + name)
  }
}

object Tree {
  implicit val treeFormat = Json.format[Tree]

  def empty = Tree(1, Set.empty, Set.empty, None, Nil, Nil)

  def createFrom(node: Node) =
    Tree(1, Set(node), Set.empty, Some(Color.RED), Nil, Nil)
}
