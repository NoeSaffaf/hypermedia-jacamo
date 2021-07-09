domain(domain(lighting, [
    action("readProperty", ["?property", "?value"],
        hasValue("?property", "?value"),
        true),
    action("writeProperty", ["?property", "?value"],
        true,
        hasValue("?property", "?value"))
])) .

problem(problem(switchOff, lighting, Facts, Goal)) :- 
    .findall(hasValue(Property, Value), hasValue(Property, Value), Facts) & goal(Goal) .

goal(hasValue("status", false)) .

+!start :
    true
    <-
    !toggle ;
    !plan .

+!toggle :
    true
    <-
    readProperty("status", Status) ;
    .print("light status: ", Status) ;
    invokeAction("toggle") ;
    readProperty("status", NewStatus) ;
    .print("light status: ", NewStatus) .

+!plan :
    domain(Domain) & problem(Pb) & goal(Goal)
    <-
    getAsPDDL(Domain, DomainStr) ;
    getAsPDDL(Pb, PbStr) ;
    .print("planning with the following domain and problem: ", DomainStr, PbStr) ;
    buildPlan(Domain, Pb, Plan) ;
    .print("found the following Jason plan: ", Plan) ;
    .add_plan(Plan) ;
    !switchOff ;
    +Goal .

{ include("$jacamoJar/templates/common-cartago.asl") }
{ include("$jacamoJar/templates/common-moise.asl") }
