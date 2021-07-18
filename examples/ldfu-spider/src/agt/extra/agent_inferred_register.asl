
/**
 This example illustrate how to create a simple agent that create its own ldfu artifact in its workspace,
 adds two ontologies, as well as inferred axioms for both ontologies.

 The "create_artifact_ldfu" plan creates an artifact to the workspace of the agent. If you want to create an artifact,
 in a shared workspace for multiple agents, you can specify it instead in the .jcm file. The instanciation of the artifacts
 takes 2 parameters, the program file for linked-data fu (which you shouldn't change), and a boolean to specify if registered
 ontologies as well as added triples should consider inferred axioms/triples.

 In this example, we consider inferred axioms, thus, we set the inferred option boolean to true.

 * @author Noé SAFFAF
*/

entryPointRegister("ttl/example_ontology.ttl").


!start.

+!start : entryPointRegister(IRI) <-
    .my_name(NAME);
    .concat("ldfu_artifact_",NAME, NAME_ART);
    makeArtifact(NAME_ART,"org.hypermedea.LinkedDataFuSpider",["get.n3",true],ART_ID);
    focus(ART_ID);
    register(IRI);
    .


{ include("$jacamoJar/templates/common-cartago.asl") }
{ include("$jacamoJar/templates/common-moise.asl") }
