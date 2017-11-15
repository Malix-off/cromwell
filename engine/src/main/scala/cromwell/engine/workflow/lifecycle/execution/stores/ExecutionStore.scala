package cromwell.engine.workflow.lifecycle.execution.stores

import common.collections.Table
import cromwell.backend.BackendJobDescriptorKey
import cromwell.core.ExecutionIndex.ExecutionIndex
import cromwell.core.ExecutionStatus._
import cromwell.core.{ExecutionIndex, JobKey}
import cromwell.engine.workflow.lifecycle.execution.WorkflowExecutionActor.{apply => _}
import cromwell.engine.workflow.lifecycle.execution.keys._
import cromwell.engine.workflow.lifecycle.execution.stores.ExecutionStore._
import wom.callable.ExecutableCallable
import wom.graph.GraphNodePort.{ConditionalOutputPort, OutputPort, ScatterGathererPort}
import wom.graph._
import wom.graph.expression.ExpressionNode

object ExecutionStore {
  type StatusTable = Table[GraphNode, ExecutionIndex.ExecutionIndex, JobKey]

  val MaxJobsToStartPerTick = 1000

  implicit class EnhancedJobKey(val key: JobKey) extends AnyVal {
    /**
      * Given a StatusStable, return true if all dependencies of this key are in the table (and therefore are in this status),
      * false otherwise.
      */
    def allDependenciesAreIn(statusTable: Table[GraphNode, ExecutionIndex.ExecutionIndex, JobKey]) = {
      def chooseIndex(port: OutputPort) = port match {
        case _: ScatterGathererPort => None
        case _ => key.index
      }

      key match {
        case scatterCollector: ScatterCollectorKey =>
          // The outputToGather is the PortBasedGraphOutputNode of the inner graph that we're collecting. Go one step upstream and then
          // find the node which will have entries in the execution store. If that has 'n' entries, then we're good to start collecting,
          statusTable.row(scatterCollector.outputNodeToGather.singleInputPort.upstream.executionNode).size == scatterCollector.scatterWidth
        case conditionalCollector: ConditionalCollectorKey =>
          val upstreamPort = conditionalCollector.outputNodeToCollect.singleInputPort.upstream
          upstreamPort.executionNode.isInStatus(chooseIndex(upstreamPort), statusTable)
        // In the general case, the dependencies are held by the upstreamPorts
        case _ => key.node.upstreamPorts forall { p => p.executionNode.isInStatus(chooseIndex(p), statusTable) }
      }
    }
  }
  
  implicit class EnhancedOutputPort(val outputPort: OutputPort) extends AnyVal {
    /**
      * Node that should be considered to determine upstream dependencies
      */
    def executionNode: GraphNode = outputPort match {
      case scatter: ScatterGathererPort => scatter.outputToGather
      case conditional: ConditionalOutputPort => conditional.outputToExpose
      case other => other.graphNode
    }
  }

  implicit class EnhancedGraphNode(val graphNode: GraphNode) extends AnyVal {

    def isInStatus(index: ExecutionIndex, table: StatusTable): Boolean = graphNode match {
      case svn: ScatterVariableNode => table.contains(svn.linkToOuterGraph.graphNode, None)
      // OuterGraphInputNodes signal that an input comes from outside the graph.
      // Depending on whether or not this input is outside of a scatter graph will change the index which we need to look at
      case ogin: OuterGraphInputNode if !ogin.preserveScatterIndex => ogin.linkToOuterGraph.executionNode.isInStatus(None, table)
      case ogin: OuterGraphInputNode => ogin.linkToOuterGraph.executionNode.isInStatus(index, table)
      case _: GraphInputNode => true
      case _ => table.contains(graphNode, index)
    }
  }

  case class ExecutionStoreUpdate(runnableKeys: List[JobKey], updatedStore: ExecutionStore)
  
  def empty = ActiveExecutionStore(Map.empty[JobKey, ExecutionStatus], needsUpdate = false)

  def apply(callable: ExecutableCallable) = {
    // Keys that are added in a NotStarted Status
    val notStartedKeys = callable.graph.nodes collect {
      case call: TaskCallNode => List(BackendJobDescriptorKey(call, None, 1))
      case expression: ExpressionNode => List(ExpressionKey(expression, None))
      case scatterNode: ScatterNode => List(ScatterKey(scatterNode))
      case conditionalNode: ConditionalNode => List(ConditionalKey(conditionalNode, None))
    }

    // There are potentially resolved workflow inputs that are default WomExpressions.
    // For now assume that those are call inputs that will be evaluated in the CallPreparation.
    // If they are actually workflow declarations then we would need to add them to the ExecutionStore so they can be evaluated.
    // In that case we would want InstantiatedExpressions so we can create an InstantiatedExpressionNode and add a DeclarationKey
    ActiveExecutionStore(notStartedKeys.flatten.map(_ -> NotStarted).toMap, notStartedKeys.nonEmpty)
  }

}

/**
  * Execution store in its nominal state
  */
final case class ActiveExecutionStore(private val statusStore: Map[JobKey, ExecutionStatus], override val needsUpdate: Boolean) extends ExecutionStore(statusStore, needsUpdate) {
  override def updateKeys(values: Map[JobKey, ExecutionStatus], needsUpdate: Boolean): ActiveExecutionStore = {
    this.copy(statusStore = statusStore ++ values, needsUpdate = needsUpdate)
  }
  override def seal: SealedExecutionStore = SealedExecutionStore(statusStore.filterNot(_._2 == NotStarted), needsUpdate)
  override def withNeedsUpdateFalse: ExecutionStore = this.copy(needsUpdate = false)
  override def withNeedsUpdateTrue: ExecutionStore = this.copy(needsUpdate = true)
}

/**
  * Execution store when the workflow is in either Failing or Aborting state. Keys in NotStarted state have been removed and
  * no new NotStarted key can be added. Other statuses can still be updated.
  */
final case class SealedExecutionStore(private val statusStore: Map[JobKey, ExecutionStatus], override val needsUpdate: Boolean) extends ExecutionStore(statusStore, false) {
  override def updateKeys(values: Map[JobKey, ExecutionStatus], needsUpdate: Boolean): SealedExecutionStore = {
    // Don't allow NotStarted keys in sealed mode
    this.copy(statusStore = statusStore ++ values.filterNot(_._2 == NotStarted), needsUpdate = needsUpdate)
  }
  override def seal: SealedExecutionStore = this
  override def withNeedsUpdateFalse: ExecutionStore = this.copy(needsUpdate = false)
  override def withNeedsUpdateTrue: ExecutionStore = this.copy(needsUpdate = true)
}

/**
  * Holds the status of all job keys in the workflow
  * @param statusStore status of job keys
  * @param needsUpdate This is a boolean meant to tell the WEA whether or not it should call "update".
  *                    The idea is to avoid unnecessary calls to "update" which is an expansive method.
  *                    
  *                    when true, something happened since the last update that could yield new runnable keys, so update should be called
  *                    when false, nothing happened between the last update and now that will yield different results so no need to call the update method
  */
sealed abstract class ExecutionStore(statusStore: Map[JobKey, ExecutionStatus], val needsUpdate: Boolean) {
  // View of the statusStore more suited for lookup based on status
  lazy val store: Map[ExecutionStatus, List[JobKey]] = statusStore.groupBy(_._2).mapValues(_.keys.toList)

  /**
    * Update key statuses and needsUpdate
    */
  protected def updateKeys(values: Map[JobKey, ExecutionStatus], needsUpdate: Boolean): ExecutionStore
  
  /**
    * Update key statuses
    */
  def updateKeys(values: Map[JobKey, ExecutionStatus]): ExecutionStore = {
    updateKeys(values, needsUpdate || values.values.exists(_.isTerminalOrRetryable))
  }

  /**
    * Returns a SealedExecutionStore: all NotStarted keys will be removed and no new NotStarted keys can be added after that
    */
  def seal: SealedExecutionStore

  /**
    * Set needsUpdate to true.
    */
  protected def withNeedsUpdateTrue: ExecutionStore

  /**
    * Resets the needsUpdate Boolean to false.
    */
  protected def withNeedsUpdateFalse: ExecutionStore
  
  /*
    * Create 2 Tables, one for keys in done status and one for keys in terminal status.
    * A Table is nothing more than a Map[R, Map[C, V]], see Table trait for more details
    * In this case, rows are GraphNodes, columns are ExecutionIndexes, and values are JobKeys
    * This allows for quick lookup of all shards for a node, as well as accessing a specific key with a 
    * (node, index) pair
   */
  lazy val (doneStatus, terminalStatus) = {
    def toTableEntry(key: JobKey) = (key.node, key.index, key)

    store.foldLeft((Table.empty[GraphNode, ExecutionIndex, JobKey], Table.empty[GraphNode, ExecutionIndex, JobKey]))({
      case ((done, terminal), (status, keys))  =>
        lazy val newMapEntries = keys map toTableEntry
        val newDone = if (status.isDoneOrBypassed) done.addAll(newMapEntries) else done
        val newTerminal = if (status.isTerminal) terminal.addAll(newMapEntries) else terminal

        newDone -> newTerminal
    })
  }

  private def keysWithStatus(status: ExecutionStatus) = store.getOrElse(status, List.empty)

  /**
    * We're done when all the keys have a terminal status,
    * which is equivalent to non of them being in a non-terminal status and faster to verify
    */
  def isDone: Boolean = {
    NonTerminalStatuses.map(keysWithStatus).forall(_.isEmpty)
  }

  def jobStatus(jobKey: JobKey): Option[ExecutionStatus] = statusStore.get(jobKey)

  def startedJobs: List[BackendJobDescriptorKey] = {
    store.filterNot({ case (s, _) => s == NotStarted}).values.toList.flatten collect {
      case k: BackendJobDescriptorKey => k
    }
  }

  override def toString: String =
    s"""
       |ExecutionStore(
       |  statusStore = {
       |    ${store.map { case (j, s) => s"$j -> ${s.mkString(System.lineSeparator + "      ", ", " + System.lineSeparator + "      ", "")}" } mkString("," + System.lineSeparator + "    ")}
       |  },
       |  needsUpdate = $needsUpdate
       |)""".stripMargin

  /**
    * If needsUpdate is true, goes through NotStarted keys and determines the ones that can be run.
    * Only computes the first MaxJobsToStartPerTick runnable keys.
    * In the process, identifies and updates the status of keys that are unstartable.
    * Returns an ExecutionStoreUpdate which is the list of runnable keys and an updated execution store.
    * 
    * If needsUpdate, returns an empty list of runnable keys and this instance of the store.
    * 
    * This method can expansive to run for very large workflows if needsUpdate is true.
    */
  def update: ExecutionStoreUpdate = if (needsUpdate) {
    // When looking for runnable keys, keep track of the ones that are unstartable so we can mark them as such
    var unstartables = Map.empty[JobKey, ExecutionStatus]

    // filter the keys that are runnable. In the process remember the ones that are unreachable
    val readyToStart = keysWithStatus(NotStarted).toStream.filter(key => {
      // A key is runnable if all its dependencies are Done
      val runnable = key.allDependenciesAreIn(doneStatus)
      
      // If the key is not runnable, but all its dependencies are in a terminal status, then it's unreachable
      if (!runnable && key.allDependenciesAreIn(terminalStatus)) unstartables = unstartables + (key -> Unstartable)
      
      // returns the runnable value for the filter
      runnable
    })

    // Compute the first ExecutionStore.MaxJobsToStartPerTick + 1 runnable keys
    val keysToStartPlusOne = readyToStart.take(MaxJobsToStartPerTick + 1).toList
    
    // Will be true if the result is truncated, in which case we'll need to do another pass later
    val truncated = keysToStartPlusOne.size > MaxJobsToStartPerTick

    // If we found unstartable keys, update their status, and set needsUpdate to true (it might unblock other keys)
    val updated = if (unstartables.nonEmpty) {
      updateKeys(unstartables, needsUpdate = true)
    // If the list was truncated, set needsUpdate to true because we'll need to do this again to get the truncated keys
    } else if (truncated) {
      withNeedsUpdateTrue
    // Otherwise we can reset it, nothing else will be runnable / unstartable until some new keys become terminal
    } else withNeedsUpdateFalse
    
    // Only take the first ExecutionStore.MaxJobsToStartPerTick from the above list.
    ExecutionStoreUpdate(keysToStartPlusOne.take(MaxJobsToStartPerTick), updated)
  } else ExecutionStoreUpdate(List.empty, this)
}