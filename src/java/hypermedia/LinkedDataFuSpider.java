package hypermedia;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.StringReader;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Paths;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.LogManager;
import java.util.logging.Logger;

import cartago.*;
import evaluation.EvaluationUtilities;
import onto.classes.*;
import edu.kit.aifb.datafu.*;
import edu.kit.aifb.datafu.consumer.impl.BindingConsumerCollection;
import edu.kit.aifb.datafu.engine.EvaluateProgram;
import edu.kit.aifb.datafu.io.input.request.EvaluateRequestOrigin;
import edu.kit.aifb.datafu.io.origins.FileOrigin;
import edu.kit.aifb.datafu.io.origins.InternalOrigin;
import edu.kit.aifb.datafu.io.origins.RequestOrigin;
import edu.kit.aifb.datafu.io.output.request.EvaluateUnsafeRequestOrigin;
import edu.kit.aifb.datafu.io.sinks.BindingConsumerSink;
import edu.kit.aifb.datafu.parser.ProgramConsumerImpl;
import edu.kit.aifb.datafu.parser.QueryConsumerImpl;
import edu.kit.aifb.datafu.parser.notation3.Notation3Parser;
import edu.kit.aifb.datafu.parser.sparql.SparqlParser;
import edu.kit.aifb.datafu.planning.EvaluateProgramConfig;
import edu.kit.aifb.datafu.planning.EvaluateProgramGenerator;
import jason.asSyntax.*;
import onto.namespaceAPI.NamingStrategy;
import onto.namespaceAPI.NamingStrategyFactory;
import onto.ontologyAPI.InferredAxiomExtractor;
import onto.ontologyAPI.OntologyExtractionManager;
import org.semanticweb.HermiT.Reasoner.ReasonerFactory;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.*;
import org.semanticweb.yars.nx.BNode;
import org.semanticweb.yars.nx.Node;
import org.semanticweb.yars.nx.Nodes;
import org.semanticweb.yars.nx.Resource;
import org.semanticweb.yars.nx.Literal;
import tools.IRITools;
import uk.ac.manchester.cs.owl.owlapi.OWLDataFactoryImpl;


import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static jason.asSyntax.ASSyntax.createStructure;

/**
 * A CArtAgO artifact for browsing Linked Data.
 * <p>
 * Contributors:
 * - Victor Charpenay (author), Mines Saint-Étienne
 * - Noé SAFFAF, Mines Saint-Étienne
 */
public class LinkedDataFuSpider extends Artifact {

	//private Pattern tripleTermPattern = Pattern.compile("rdf\\((.*)\",\"(.*)\",\"(.*)\"\\)");
	private Pattern tripleTermPattern = Pattern.compile("rdf\\((.*),(.*),(.*)\\)");
	private static final String COLLECT_QUERY = "construct { ?s ?p ?o . } where { ?s ?p ?o . }";
	private Program program;
	private BindingConsumerCollection triples;
	private Timer timer;


	//Case Inferred;
	private boolean inferred;
	private ReasonerFactory reasonerFactory;

	//For triple matching
	private static final String rdfType = "http://www.w3.org/1999/02/22-rdf-syntax-ns#type";



	//Register Save
	private OWLOntology owlOntology;
	private Set<String> registeredURIset;
	private Map<OWLAxiomWrapper, ObsProperty> observablePropertyOntologyMap;
	private Map<OWLAxiomWrapper, ObsProperty> observablePropertyTripleMap;
	private Set<OWLAxiomWrapper> owlAxiomWrapperSet;

	//NamingStrategy
	private Set<NamingStrategy> namingStrategySet;

	//DataFactory
	private OWLDataFactory dataFactory;

	//Evaluation
	private boolean evaluation = false;
	private long lastRegisteredTime;
	private long totalTime;
	private List<Long> registeredTimeList;


	public LinkedDataFuSpider() {
		// set logging level to warning
		Logger log = Logger.getLogger("edu.kit.aifb.datafu");
		//log.setLevel(Level.WARNING);
		log.setLevel(Level.OFF);
		LogManager.getLogManager().addLogger(log);
	}

	/**
	 * Initiate the artifact by passing a programFile for the ldfu engine
	 * @param programFile program file for ldfu
	 * @param inferred option for enabling inferred ontologies
	 */
	public void init(String programFile, boolean inferred) {
		try {
			InputStream is = new FileInputStream(programFile);
			Origin base = new FileOrigin(new File(programFile), FileOrigin.Mode.READ, null);
			Notation3Parser n3Parser = new Notation3Parser(is);
			ProgramConsumerImpl programConsumer = new ProgramConsumerImpl(base);

			n3Parser.parse(programConsumer, base);
			is.close();

			program = programConsumer.getProgram(base);

			QueryConsumerImpl queryConsumer = new QueryConsumerImpl(base);
			SparqlParser sparqlParser = new SparqlParser(new StringReader(COLLECT_QUERY));
			sparqlParser.parse(queryConsumer, new InternalOrigin(""));
			ConstructQuery query = queryConsumer.getConstructQueries().iterator().next();

			triples = new BindingConsumerCollection();
			program.registerConstructQuery(query, new BindingConsumerSink(triples));

			initParameters(inferred);

			// TODO recurrent polling with computation of +/- delta?
			//timer = new Timer();
			//timer.schedule(new TimerTask() { @Override public void run() { crawl(); } }, 0, 10000);
		} catch (Exception e) {
			e.printStackTrace();
			// TODO report error

			program = null;
		}
	}

	/**
	 * Supercharge of init in case we want to run in evaluation mode
	 * @param programFile program file for ldfu
	 * @param inferred option for enabling inferred ontologies
	 * @param evaluation if true, enable the evaluation option
	 */
	public void init(String programFile, boolean inferred, boolean evaluation){
		init(programFile, inferred);
		this.evaluation = evaluation;
		if (evaluation){
			this.totalTime = 0;
			this.registeredTimeList =  new ArrayList<>();
		}

	}
	/**
	 * Initiate parameters and prepare the belief naming strategy by computing all known namespaces
	 */
	private void initParameters(boolean inferred) {

		registeredURIset = new HashSet<>();
		owlAxiomWrapperSet = new HashSet<>();
		observablePropertyOntologyMap = new HashMap<>();
		observablePropertyTripleMap = new HashMap<>();
		this.inferred = inferred;
		reasonerFactory = new ReasonerFactory();

		try {
			owlOntology = OWLManager.createOWLOntologyManager().createOntology();
		} catch (OWLOntologyCreationException e){
			e.printStackTrace();
		}

		namingStrategySet = NamingStrategyFactory.createAllNamingStrategySet();
		for (NamingStrategy ns : namingStrategySet){
			ns.init();
		}

		dataFactory = new OWLDataFactoryImpl();
	}

	/**
	 * Deprecated, better use a verification from the agent space
	 */
	@OPERATION
	public void isPresent(String st1, String st2, String st3, OpFeedbackParam<Boolean> result) {
		result.set(hasObsPropertyByTemplate("rdf", st1, st2, st3));
	}

	/**
	 * External action to register all the ontologies in the pending list, merge them into one merged ontology and create unary/binary beliefs (observable properties)
	 * of class declarations object property declarations, data property declarations, owl annotations, and class insertions (instances of indivual) from the ontologies
	 * files. It is possible to have inferred axioms.
	 */
	@OPERATION
	public void register(String originURI) {
		if (evaluation)
		registeredURIset.add(originURI);
		owlOntology = OntologyExtractionManager.addOntology(originURI, owlOntology, registeredURIset);

		//Case We want to have inferred axioms
		if (inferred) {
			InferredAxiomExtractor inferredAxiomExtractor = new InferredAxiomExtractor(owlOntology, reasonerFactory);
			inferredAxiomExtractor.precomputeInferredAxioms();
			owlOntology = inferredAxiomExtractor.getInferredOntology();
		}

		//We precompute all naming strategy that relies on an ontology
		for (NamingStrategy ns : namingStrategySet){
			ns.precompute(owlOntology);
		}

		owlAxiomWrapperSet.addAll(getOwlAxiomWrapperSet(owlOntology));

		/*
		if (!owlAxiomWrapperSet.isEmpty()){
			hasMadeRegister = true;
		} else {
			hasMadeRegister = false;
		}*/

		for (OWLAxiomWrapper axiom : owlAxiomWrapperSet){
			String propFullName = axiom.getPropertyFullName();
			String propName = axiom.getPropertyName();
			if (propName == null || propName.isBlank()){
				continue;
			}
			List<Object> argumentsList = axiom.getPropertyArguments();
			if (argumentsList.size() == 1 && !hasObsPropertyByTemplate(propName,argumentsList.get(0))){
				ObsProperty obsProperty = defineObsProperty(propName,argumentsList.get(0));
				Structure s = createStructure("predicate_uri", new Atom(propFullName));
				obsProperty.addAnnot(s);
				observablePropertyOntologyMap.put(axiom, obsProperty);
			} else if (argumentsList.size() == 2 && !hasObsPropertyByTemplate(propName, argumentsList.get(0), argumentsList.get(1))) {
				ObsProperty obsProperty = defineObsProperty(propName,argumentsList.get(0), argumentsList.get(1));
				Structure s = createStructure("predicate_uri", new Atom(propFullName));
				obsProperty.addAnnot(s);
				observablePropertyOntologyMap.put(axiom, obsProperty);
			}
		}
	}

	/**
	 * External action to unregister an ontology by key, it recalculate all axioms with the removed ontology and compare it to the previous ones, and removes
	 * from the observable ontology database the delta difference
	 */
	@OPERATION
	public void unregister(String originURI){
		//hasMadeRegister &&
		if (registeredURIset.contains(originURI)){
			registeredURIset.remove(originURI);

			OWLOntology revisedOwlOntology = OntologyExtractionManager.extractOntologyFromRegisteredSet(registeredURIset);

			//Case We want to have inferred axioms
			if (inferred) {
				InferredAxiomExtractor inferredAxiomExtractor = new InferredAxiomExtractor(revisedOwlOntology, reasonerFactory);
				inferredAxiomExtractor.precomputeInferredAxioms();
				owlOntology = inferredAxiomExtractor.getInferredOntology();
			}

			Set<OWLAxiomWrapper> setRevisedOWLAxiomJasonWrapper = getOwlAxiomWrapperSet(revisedOwlOntology);
			Set<OWLAxiomWrapper> deltaAxiomSet = owlAxiomWrapperSet;
			deltaAxiomSet.removeAll(setRevisedOWLAxiomJasonWrapper);;

			int numberDeleted = 0;
			Set<OWLAxiomWrapper> owlAxiomWrapperSetToRemove = new HashSet<>();
			for (OWLAxiomWrapper axiomWrapper : observablePropertyOntologyMap.keySet()){
				if (owlAxiomWrapperSet.contains(axiomWrapper)){
					ObsProperty o = observablePropertyOntologyMap.get(axiomWrapper);
					if(o.getValues().length == 1){
						removeObsPropertyByTemplate(o.getName(),o.getValue(0));
					} else if (o.getValues().length == 2){
						removeObsPropertyByTemplate(o.getName(),o.getValue(0),o.getValue(1));
					}
					owlAxiomWrapperSetToRemove.add(axiomWrapper);
					numberDeleted++;
				}
			}
			for (OWLAxiomWrapper toRemove : owlAxiomWrapperSetToRemove){
				observablePropertyOntologyMap.remove(toRemove);
			}

			System.out.println("Number of ObsProperties deleted : "+numberDeleted);
		} else {
			System.out.println("No register with this URI has been made");
		}
	}

	/**
	 * External Action to check if the ontology is Consistent (has NamedIndividual instance of an unsatisfiable class)
	 * @param b A boolean parameter to unify with the response of the External action
	 * @param report A string parameter to unify with the message report of the External action
	 */
	@OPERATION
	public void isConsistent(OpFeedbackParam<Boolean> b, OpFeedbackParam<String> report){
		if (owlOntology == null){
			b.set(true);
			report.set("No ontology is saved");
		}

		InferredAxiomExtractor inferredAxiomExtractor = new InferredAxiomExtractor(owlOntology, reasonerFactory);
		if (inferredAxiomExtractor.checkConsistency()){
			b.set(true);
			report.set("The ontology is consistent");
		} else {
			b.set(false);
			report.set("Warning : The ontology is not consistent");
		}
	}

	/**
	 * Eternal Action to check if the ontology is Satisfiable
	 * @param displayClasses An option to feed the report message with all the unsatisfiable onto.classes
	 * @param b A boolean parameter to unify with the response of the External action
	 * @param report A string parameter to unify with the message report of the External action
	 */
	@OPERATION
	public void isSatisfiable(boolean displayClasses, OpFeedbackParam<Boolean> b, OpFeedbackParam<String> report){
		if (owlOntology == null){
			b.set(true);
			report.set("No ontology is saved");
		}
		InferredAxiomExtractor inferredAxiomExtractor = new InferredAxiomExtractor(owlOntology, reasonerFactory);
		if (inferredAxiomExtractor.checkSatisfiability()) {
			b.set(true);
			report.set("The ontology is satisfiable");
		} else {
			b.set(false);
			if (displayClasses){
				String s = "Unsatisfiable Axioms : ";
				for (OWLClass c : inferredAxiomExtractor.getUnsatisfiableClasses()){
					s += "\n" + c.toString();
				}
				report.set("Warning : The ontology is not satisfiable.\n"+s);
			} else {
				report.set("Warning : The ontology is not satisfiable.");
			}
		}
	}


	/**
	 * Deprecated
	 * @param st1
	 * @param st2
	 * @param st3
	 * @param index
	 * @param st4
	 */
	@OPERATION
	public void update(String st1, String st2, String st3, int index, String st4) {
		ObsProperty op = getObsPropertyByTemplate("rdf", st1, st2, st3);
		op.updateValue(index, st4);
	}

	/**
	 * External action to execute the Linked Data program and notifies agent with collected triples and their unary
	 * binary axioms according to the already registered ontologies. Can accept local file and can have the inferred
	 * axioms (Class assertion only) of the unary/binary beliefs
	 * @param originURI The entrypoint for the data graph file to crawl, can be a local path if the option local is activated
	 */
	@OPERATION
	public void crawl(String originURI){
		long startTime = 0L;
		long endTime = 0L;
		if (evaluation){
			startTime = System.currentTimeMillis();
		}

		if (program == null) return;

		EvaluateProgramConfig config = new EvaluateProgramConfig();
		//config.setThreadingModel(EvaluateProgramConfig.ThreadingModel.SERIAL);
		EvaluateProgram eval = new EvaluateProgramGenerator(program, config).getEvaluateProgram();
		eval.start();


		boolean uriCreated = false;
		URI uri;

		try {
			if (!uriCreated && originURI.startsWith("http")) {
				uri = URI.create(originURI);
				eval.getInputOriginConsumer().consume(new RequestOrigin(uri, Request.Method.GET));
				uriCreated = true;
			}
		} catch (InterruptedException e) {
			e.printStackTrace();
		}


		try {
			if (!uriCreated) {
				eval.getInputOriginConsumer().consume(new FileOrigin(new File(originURI), FileOrigin.Mode.READ, null));
				uriCreated = true;
			}
		} catch (InterruptedException e) {
			e.printStackTrace();
		}

		try {
			if (!uriCreated) {
				uri = URI.create("file:///" + Paths.get(originURI).toAbsolutePath().toString().replaceAll("\\\\","//"));
				eval.getInputOriginConsumer().consume(new RequestOrigin(uri, Request.Method.GET));
				uriCreated = true;
			}
		} catch (InterruptedException e) {
			e.printStackTrace();
			System.out.println("URI/FilePath is invalid");
			return;
		}


		try {
			eval.awaitIdleAndFinish();
			eval.shutdown();
		} catch (InterruptedException e) {
			e.printStackTrace();
		}

		if (evaluation){
			endTime = System.currentTimeMillis();
			lastRegisteredTime = endTime - startTime;
			totalTime += lastRegisteredTime;
			registeredTimeList.add(lastRegisteredTime);
		}

		String subject;
		String predicate;
		String object;

		Set<OWLClass> owlClassSet = owlOntology.getClassesInSignature();
		Set<OWLObjectProperty> owlObjectPropertySet = owlOntology.getObjectPropertiesInSignature();
		Set<OWLDataProperty> owlDataPropertySet = owlOntology.getDataPropertiesInSignature();

		Set<OWLAxiom> owlCrawledAxiomSet = new HashSet<>();
		for (Binding binding : triples.getCollection()) {
			Node[] st = binding.getNodes().getNodeArray();
			subject = st[0].getLabel();
			predicate = st[1].getLabel();
			object = st[2].getLabel();
			defineObsProperty("rdf", subject, predicate, object);

			OWLAxiom owlAxiom = getOwlAxiomFromTriple(subject, predicate, object, owlClassSet, owlObjectPropertySet, owlDataPropertySet, dataFactory);
			if (owlAxiom != null){
				owlCrawledAxiomSet.add(owlAxiom);
			}
		}


		try {
			OWLOntology copiedOntology = OntologyExtractionManager.copyOntology(owlOntology);
			copiedOntology = OntologyExtractionManager.addAxiomToOntology(owlCrawledAxiomSet, copiedOntology);
			if (inferred){
				InferredAxiomExtractor inferredAxiomExtractor = new InferredAxiomExtractor(copiedOntology, reasonerFactory);
				inferredAxiomExtractor.precomputeInferredAxioms();
				copiedOntology = inferredAxiomExtractor.getInferredOntology();
			}

			Set<OWLAxiomWrapper> owlCrawledAxiomWrapperSet = getOwlAxiomWrapperSet(copiedOntology);

			for (OWLAxiomWrapper axiom : owlCrawledAxiomWrapperSet){
				String propFullName = axiom.getPropertyFullName();
				String propName = axiom.getPropertyName();
				if (propName == null || propName.isBlank()){
					continue;
				}
				List<Object> argumentsList = axiom.getPropertyArguments();
				if (!observablePropertyTripleMap.containsKey(axiom)){
					if (argumentsList.size() == 1 && !hasObsPropertyByTemplate(propName,argumentsList.get(0))){
						ObsProperty obsProperty = defineObsProperty(propName,argumentsList.get(0));
						Structure s = createStructure("predicate_uri", new Atom(propFullName));
						obsProperty.addAnnot(s);
						observablePropertyTripleMap.put(axiom, obsProperty);
					} else if (argumentsList.size() == 2 && !hasObsPropertyByTemplate(propName, argumentsList.get(0), argumentsList.get(1))) {
						ObsProperty obsProperty = defineObsProperty(propName,argumentsList.get(0), argumentsList.get(1));
						Structure s = createStructure("predicate_uri", new Atom(propFullName));
						obsProperty.addAnnot(s);
						observablePropertyTripleMap.put(axiom, obsProperty);
					}
				}
			}
		} catch (OWLOntologyCreationException e) {
			e.printStackTrace();
			return;
		}
	}

	/**
	 * performs a GET request and updates the belief base as the result.
	 */
	@OPERATION
	public void get(String originURI) throws URISyntaxException {
		long startTime = 0L;
		long endTime = 0L;
		if (evaluation){
			startTime = System.currentTimeMillis();
		}

		RequestOrigin req;

		boolean uriCreated = false;
		URI uri;

		if (!uriCreated && originURI.startsWith("http")) {
			uri = URI.create(originURI);
		} else {
			//Not likely to work because it points to a local file which can't handle http request, to modify
			uri = URI.create("file:///" + Paths.get(originURI).toAbsolutePath().toString().replaceAll("\\\\", "//"));
		}

		req = new RequestOrigin(uri, Request.Method.GET);
		BindingConsumerCollection triples = new BindingConsumerCollection();
		EvaluateRequestOrigin eval = new EvaluateRequestOrigin();

		eval.setTripleCallback(new BindingConsumerSink(triples));
		try {
			eval.consume(req);
			eval.shutdown();
		} catch (InterruptedException e) {
			e.printStackTrace();
		}

		if (evaluation){
			endTime = System.currentTimeMillis();
			lastRegisteredTime = endTime - startTime;
			totalTime += lastRegisteredTime;
			registeredTimeList.add(lastRegisteredTime);

		}

		// authoritative subject
		// TODO graph name available?
		if (hasObsPropertyByTemplate("rdf", originURI, null, null)) {
			removeObsPropertyByTemplate("rdf", originURI, null, null);
		}

		String subject;
		String predicate;
		String object;

		Set<OWLClass> owlClassSet = owlOntology.getClassesInSignature();
		Set<OWLObjectProperty> owlObjectPropertySet = owlOntology.getObjectPropertiesInSignature();
		Set<OWLDataProperty> owlDataPropertySet = owlOntology.getDataPropertiesInSignature();


		Set<OWLAxiom> owlCrawledAxiomSet = new HashSet<>();
		for (Binding binding : triples.getCollection()) {
			Node[] st = binding.getNodes().getNodeArray();
			subject = st[0].getLabel();
			predicate = st[1].getLabel();
			object = st[2].getLabel();
			defineObsProperty("rdf", subject, predicate, object);
			OWLAxiom owlAxiom = getOwlAxiomFromTriple(subject, predicate, object, owlClassSet, owlObjectPropertySet, owlDataPropertySet, dataFactory);
			if (owlAxiom != null){
				owlCrawledAxiomSet.add(owlAxiom);
			}}

		try {
			OWLOntology copiedOntology = OntologyExtractionManager.copyOntology(owlOntology);
			copiedOntology = OntologyExtractionManager.addAxiomToOntology(owlCrawledAxiomSet, copiedOntology);
			if (inferred) {
				InferredAxiomExtractor inferredAxiomExtractor = new InferredAxiomExtractor(copiedOntology, reasonerFactory);
				inferredAxiomExtractor.precomputeInferredAxioms();
				copiedOntology = inferredAxiomExtractor.getInferredOntology();
			}
			Set<OWLAxiomWrapper> owlCrawledAxiomWrapperSet = getOwlAxiomWrapperSet(copiedOntology);

			for (OWLAxiomWrapper axiom : owlCrawledAxiomWrapperSet) {
				String propFullName = axiom.getPropertyFullName();
				String propName = axiom.getPropertyName();
				if (propName == null || propName.isBlank()){
					continue;
				}
				List<Object> argumentsList = axiom.getPropertyArguments();
				if (!observablePropertyTripleMap.containsKey(axiom)) {
					if (argumentsList.size() == 1 && !hasObsPropertyByTemplate(propName,argumentsList.get(0))) {
						ObsProperty obsProperty = defineObsProperty(propName, argumentsList.get(0));
						Structure s = createStructure("predicate_uri", new Atom(propFullName));
						obsProperty.addAnnot(s);
						observablePropertyTripleMap.put(axiom, obsProperty);
					} else if (argumentsList.size() == 2 && !hasObsPropertyByTemplate(propName, argumentsList.get(0), argumentsList.get(1))) {
						ObsProperty obsProperty = defineObsProperty(propName, argumentsList.get(0), argumentsList.get(1));
						Structure s = createStructure("predicate_uri", new Atom(propFullName));
						obsProperty.addAnnot(s);
						observablePropertyTripleMap.put(axiom, obsProperty);
					}
				}
			}
		} catch (OWLOntologyCreationException e) {
			e.printStackTrace();
			return;
		}
	}

	/**
	 * performs a PUT request with the given input triples, of the form [rdf(S, P, O), rdf(S, P, O), ...].
	 */
	@OPERATION
	public void put(String originURI, Object[] payload) {
		try {
			RequestOrigin req = new RequestOrigin(new URI(originURI), Request.Method.PUT);

			Set<Nodes> triples = new HashSet<>();
			for (Object term : payload) {
				// terms are exposed as strings to CArtAgO artifacts
				Matcher m = tripleTermPattern.matcher((String) term);
				if (m.matches()) {
					Nodes n = new Nodes(asNode(m.group(1)), asNode(m.group(2)), asNode((m.group(3))));
					triples.add(n);
				}
			}

			/*
			System.out.println("Document : " + req.getDocument());
			System.out.println("State : "+ req.getState());
			System.out.println("Group : "+ req.getGroup());
			Collection<HeaderField> headerFields = req.getHeaders();
			if (headerFields != null) {
				for (HeaderField hf : headerFields){
					System.out.println(hf.getName() + " ::: "+ hf.getValue());
				}
			}*/


			req.setTriplesPayload(triples);

			EvaluateUnsafeRequestOrigin eval = new EvaluateUnsafeRequestOrigin();
			eval.consume(req);
			eval.shutdown();
		} catch (InterruptedException e) {
			e.printStackTrace();
			// TODO recover or ignore?
		} catch (URISyntaxException e) {
			// TODO throw it to make operation fail?
			e.printStackTrace();
		}
	}

	@OPERATION
	public void post(String originURI, Object[] payload) {
		try {
			RequestOrigin req = new RequestOrigin(new URI(originURI), Request.Method.POST);

			Set<Nodes> triples = new HashSet<>();
			for (Object term : payload) {
				// terms are exposed as strings to CArtAgO artifacts
				Matcher m = tripleTermPattern.matcher((String) term);
				if (m.matches()) {
					triples.add(new Nodes(asNode(m.group(1)), asNode(m.group(2)), asNode((m.group(3)))));
				}
			}
			req.setTriplesPayload(triples);

			EvaluateUnsafeRequestOrigin eval = new EvaluateUnsafeRequestOrigin();
			eval.consume(req);
			eval.shutdown();
		} catch (InterruptedException e) {
			e.printStackTrace();
			// TODO recover or ignore?
		} catch (URISyntaxException e) {
			// TODO throw it to make operation fail?
			e.printStackTrace();
		}
	}


	@OPERATION
	public void delete(String originURI, Object[] payload) {
		try {
			RequestOrigin req = new RequestOrigin(new URI(originURI), Request.Method.DELETE);

			Set<Nodes> triples = new HashSet<>();
			for (Object term : payload) {
				// terms are exposed as strings to CArtAgO artifacts
				Matcher m = tripleTermPattern.matcher((String) term);
				if (m.matches()) {
					triples.add(new Nodes(asNode(m.group(1)), asNode(m.group(2)), asNode((m.group(3)))));
				}
			}
			req.setTriplesPayload(triples);

			EvaluateUnsafeRequestOrigin eval = new EvaluateUnsafeRequestOrigin();
			eval.consume(req);
			eval.shutdown();
		} catch (InterruptedException e) {
			e.printStackTrace();
			// TODO recover or ignore?
		} catch (URISyntaxException e) {
			// TODO throw it to make operation fail?
			e.printStackTrace();
		}
	}

	@OPERATION
	public void getLastRegisteredTime(OpFeedbackParam<Double> op_lastRegisteredTime){
		if (evaluation){
			op_lastRegisteredTime.set(new Double(lastRegisteredTime));
		} else {
			System.out.println("Operation failed : Cannot get LastRegisteredTime because the evaluation mode is turned off, please instanciate the artifact by enabling the evaluation mode");
		}
	}

	@OPERATION
	public void getTotalTime(OpFeedbackParam<Double> op_totalTime){
		if (evaluation){
			op_totalTime.set(new Double(totalTime));
		} else {
			System.out.println("Operation failed : Cannot get TotalTime because the evaluation mode is turned off, please instanciate the artifact by enabling the evaluation mode");
		}
	}

	@OPERATION
	public void writeEvaluationReport(String filename){
		if (evaluation){
			EvaluationUtilities.writeData(registeredTimeList, filename);
		} else {
			System.out.println("Operation failed : Cannot write an evaluation report because the evaluation mode is turned off, please instanciate the artifact by enabling the evaluation mode");
		}
	}

	private OWLAxiom getOwlAxiomFromTriple(String subject, String predicate, Object object, Set<OWLClass> owlClassSet, Set<OWLObjectProperty> owlObjectProperties, Set<OWLDataProperty> owlDataProperties, OWLDataFactory dataFactory)
	{
		if (predicate.equals(rdfType)){
			for (OWLClass c : owlClassSet) {
				if (IRITools.removeWrapperIRI(c.toString()).equals(object)){
					OWLNamedIndividual namedIndividual = dataFactory.getOWLNamedIndividual(IRI.create(subject));
					OWLClassAssertionAxiom classAssertion = dataFactory.getOWLClassAssertionAxiom(c, namedIndividual);
					return classAssertion;
				}
			}

			//Case we didn't find the type class
			return null;

		} else {
			//We check all properties
			for (OWLObjectProperty op : owlObjectProperties){
				if (predicate.equals(IRITools.removeWrapperIRI(op.toString()))){
					OWLObjectPropertyAssertionAxiom propertyAssertionAxiom =
							dataFactory.getOWLObjectPropertyAssertionAxiom(
								op,
								dataFactory.getOWLNamedIndividual(IRI.create(subject)),
								dataFactory.getOWLNamedIndividual(IRI.create(object.toString()))
						);
					return propertyAssertionAxiom;
				}
			}

			for (OWLDataProperty dp : owlDataProperties){
				if (predicate.equals(IRITools.removeWrapperIRI(dp.toString()))){
					OWLLiteral literal = null;
					if (object instanceof String){
						literal = dataFactory.getOWLLiteral((String) object);
					} else if (object instanceof Boolean){
						literal = dataFactory.getOWLLiteral((Boolean) object);
					} else if (object instanceof Integer){
						literal = dataFactory.getOWLLiteral((Integer) object);
					} else if (object instanceof Double){
						literal = dataFactory.getOWLLiteral((Double) object);
					}

					OWLDataPropertyAssertionAxiom propertyAssertionAxiom =
							dataFactory.getOWLDataPropertyAssertionAxiom(
									dp,
									dataFactory.getOWLNamedIndividual(IRI.create(subject)),
									literal);
					return propertyAssertionAxiom;
				}
			}
		}
		return null;
	}


	private Set<OWLAxiomWrapper> getOwlAxiomWrapperSet(OWLOntology o){
		Set<OWLAxiomWrapper> owlAxiomWrapperSet = new HashSet<>();
		for (OWLAxiom axiom : o.getAxioms()){
			owlAxiomWrapperSet.add(new OWLAxiomWrapper(axiom, namingStrategySet));
		}
		return owlAxiomWrapperSet;
	}

	private Node asNode(String lexicalForm) {
		if (lexicalForm.startsWith("http")) {
			return new Resource(lexicalForm);
		} else if (lexicalForm.startsWith("_:")) {
			return new BNode((lexicalForm));
		} else if (lexicalForm.matches("^\\d+$")) {
			return new Literal(lexicalForm, new Resource("http://www.w3.org/2001/XMLSchema#integer"));
		} else if (lexicalForm.matches("^\\d+\\.\\d*$")) {
			return new Literal(lexicalForm, new Resource("http://www.w3.org/2001/XMLSchema#decimal"));
		} else if (lexicalForm.matches("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}")) {
			return new Literal(lexicalForm, new Resource("http://www.w3.org/2001/XMLSchema#dateTime"));
		} else {
			return new Literal(lexicalForm);
		}
	}
}
