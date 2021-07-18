/**
 * @author Noé SAFFAF
 */

entryPointCrawl("http://localhost:3030/dataLdfu?graph=evaluationTest").

!testUnit.

+!create_artifact_cpu : true <-
    .my_name(NAME);
    .concat("CPUTracker_artifact_",NAME, NAME_ART);
    makeArtifact(NAME_ART,"profilingArtifacts.CPUTrackerArtifact",[],ART_ID);
    focus(ART_ID);
    .

+!create_artifact_ldfu : true <-
     .my_name(NAME);
     .concat("ldfu_artifact_",NAME, NAME_ART);
     makeArtifact(NAME_ART,"hypermedia.LinkedDataFuSpider",["get.n3"],ART_ID);
     focus(ART_ID);
     .

+!testUnit : true <-
    !create_artifact_ldfu;
    !create_artifact_cpu;
	.print("Test : Unit measure crawl test");
	!profileWithCPUArtifact;
	.

+!profileWithCPUArtifact : true <-
     for (.range(I,1,100)){
        for (entryPointCrawl(IRI)){
            startTimeMeasure;
            crawl(IRI);
            endTimeMeasure(TIME);
            .print("Crawl exec time : ",TIME);
            removeAllObsPropertiesBinding;
        };
     };
     writeEvaluationReport("agentCrawlData.csv");
     .

{ include("$jacamoJar/templates/common-cartago.asl") }
{ include("$jacamoJar/templates/common-moise.asl") }
