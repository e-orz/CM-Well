name := "cmwell-stortill"

libraryDependencies ++= {
  val dm = dependenciesManager.value
  Seq(
    dm("com.typesafe.akka", "akka-stream"),
    dm("com.lightbend.akka", "akka-stream-alpakka-cassandra"),
    dm("org.rogach", "scallop")
  )
}

packMain := Map(
  "addProtocol"     -> "cmwell.operations.modifier.AddProtocolField",
  "verifyProtocol"  -> "cmwell.operations.modifier.VerifyProtocolField"
)

fullTest := (test in Test).value