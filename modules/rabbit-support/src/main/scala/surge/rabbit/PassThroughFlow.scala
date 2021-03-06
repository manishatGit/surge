// Copyright © 2017-2021 UKG Inc. <https://www.ukg.com>

package surge.rabbit

import akka.NotUsed
import akka.stream.scaladsl.{ Broadcast, Flow, GraphDSL, Keep, ZipWith }
import akka.stream.{ FlowShape, Graph }

// FIXME This is highly innefficient but still used in the rabbit support in the sink.  We should see if we can get rid of that dependency
object PassThroughFlow {
  def apply[In, Out](processingFlow: Flow[In, Out, NotUsed]): Graph[FlowShape[In, (Out, In)], NotUsed] =
    apply[In, Out, (Out, In)](processingFlow, Keep.both)

  def apply[In, Out, Combined](processingFlow: Flow[In, Out, NotUsed], output: (Out, In) => Combined): Graph[FlowShape[In, Combined], NotUsed] =
    Flow.fromGraph(GraphDSL.create() { implicit builder =>
      {
        import GraphDSL.Implicits._

        val broadcast = builder.add(Broadcast[In](2))
        val zip = builder.add(ZipWith[Out, In, Combined]((left, right) => output(left, right)))

        broadcast.out(0) ~> processingFlow ~> zip.in0
        broadcast.out(1) ~> zip.in1

        FlowShape(broadcast.in, zip.out)
      }
    })
}
