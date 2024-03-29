package org.hypermedea;

import cartago.Artifact;
import cartago.OPERATION;
import cartago.ObsProperty;
import cartago.OpFeedbackParam;


import edu.kit.aifb.datafu.*;
import edu.kit.aifb.datafu.consumer.impl.BindingConsumerCollection;
import edu.kit.aifb.datafu.engine.EvaluateProgram;
import edu.kit.aifb.datafu.io.input.request.EvaluateRequestOrigin;
import edu.kit.aifb.datafu.io.origins.FileOrigin;
import edu.kit.aifb.datafu.io.origins.InputOrigin;
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
import jason.asSyntax.ASSyntax;
import jason.asSyntax.Atom;
import jason.asSyntax.StringTerm;
import jason.asSyntax.Structure;

import openllet.owlapi.PelletReasonerFactory;
import uk.ac.manchester.cs.jfact.JFactFactory;

import org.hypermedea.owl.NamingStrategyFactory;
import org.hypermedea.owl.OWLAxiomWrapper;
import org.semanticweb.HermiT.ReasonerFactory;

import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.*;
import org.semanticweb.owlapi.reasoner.OWLReasoner;
import org.semanticweb.owlapi.reasoner.OWLReasonerFactory;
import org.semanticweb.owlapi.reasoner.structural.StructuralReasonerFactory;
import org.semanticweb.owlapi.util.*;
import org.semanticweb.owlapi.vocab.OWLRDFVocabulary;
import org.semanticweb.yars.nx.*;
import org.semanticweb.yars.nx.namespace.XSD;
import org.hypermedea.tools.Identifiers;
import uk.ac.manchester.cs.owl.owlapi.OWLDataFactoryImpl;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.StringReader;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.LogManager;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A CArtAgO artifact for browsing Linked Data.
 *
 * @author Victor Charpenay, Noé Saffaf
 */
public class LinkedDataFuSpider extends Artifact {

	/**
	 * Manager that listens to changes in the underlying root ontology
	 * and adds/removes corresponding observable properties.
	 */
	private class ObsPropertyManager implements OWLOntologyChangeListener, OWLOntologyChangeVisitor {

		private final Map<OWLAxiom, ObsProperty> propertiesByAxiom = new HashMap<>();
		private final Map<OWLOntology, Set<ObsProperty>> propertiesByOntology = new HashMap<>();
		private final Set<ObsProperty> inferredProperties = new HashSet<>();

		@Override
		public void ontologiesChanged(List<? extends OWLOntologyChange> list) {
			for (OWLOntologyChange c : list) c.accept(this);
			updateInferredProperties();
		}
		// Todo : Perhaps consider registered OWL files that the current OWl file uses

		@Override
		public void visit(AddImport addImport) {
			IRI ontologyIRI = addImport.getImportDeclaration().getIRI();
			OWLOntology o = ontologyManager.getOntology(ontologyIRI);

			Set<ObsProperty> properties = definePropertiesForAxioms(o.getAxioms());
			propertiesByOntology.put(o, properties);
		}

		@Override
		public void visit(RemoveImport removeImport) {
			IRI ontologyIRI = removeImport.getImportDeclaration().getIRI();
			OWLOntology o = ontologyManager.getOntology(ontologyIRI);

			if (propertiesByOntology.containsKey(o)) removeProperties(propertiesByOntology.get(o));
		}



		@Override
		public void visit(AddAxiom change) {
			if (change.getOntology().equals(rootOntology)) {
				OWLAxiom axiom = change.getChangeData().getAxiom();
				propertiesByAxiom.put(axiom,definePropertyForAxiom(axiom));
			}
		}

		@Override
		public void visit(RemoveAxiom change) {
			if (change.getOntology().equals(rootOntology)) {
				OWLAxiom axiom = change.getChangeData().getAxiom();
				if (propertiesByAxiom.containsKey(axiom)){
					ObsProperty propertyToRemove = propertiesByAxiom.get(axiom);
					if (propertyToRemove.getValues().length == 1) {
						removeObsPropertyByTemplate(propertyToRemove.getName(), propertyToRemove.getValue(0));
					} else if (propertyToRemove.getValues().length == 2) {
						removeObsPropertyByTemplate(propertyToRemove.getName(), propertyToRemove.getValue(0), propertyToRemove.getValue(1));
					}
				}
			}
		}

		private void updateInferredProperties() {
			removeProperties(inferredProperties);
			inferredProperties.clear();

			List<InferredAxiomGenerator<? extends OWLAxiom>> generators = new ArrayList<>();

			generators.add(new InferredClassAssertionAxiomGenerator());
			generators.add(new InferredPropertyAssertionGenerator());

			// TODO are owl:sameAs and owl:differentFrom included?

			for (InferredAxiomGenerator<? extends OWLAxiom> gen : generators) {
				Set<? extends OWLAxiom> axioms = gen.createAxioms(ontologyManager.getOWLDataFactory(), reasoner);
				//Removes same overlapping inferred axioms
				Set<OWLAxiom> uniqueAxioms = new HashSet<>();
				for (OWLAxiom axiom : (Set<OWLAxiom>) axioms){
					if (!propertiesByAxiom.containsKey(axiom)) uniqueAxioms.add(axiom);
				}
				inferredProperties.addAll(definePropertiesForAxioms(uniqueAxioms));
			}

			for (ObsProperty p : inferredProperties) {
				Atom annotation = ASSyntax.createAtom("inferred");
				p.addAnnot(annotation);
			}
		}
	}

	private static final String COLLECT_QUERY = "construct { ?s ?p ?o . } where { ?s ?p ?o . }";
	private static final String RDF_TYPE = OWLRDFVocabulary.RDF_TYPE.toString();

	private static final String CRAWLED_ASSERTIONS_IRI = String.format("urn:uuid:%s", UUID.randomUUID());
	private static final String PREDICATE_IRI_FUNCTOR = "predicate_uri";

	private Pattern tripleTermPattern = Pattern.compile("rdf\\((.*),(.*),(.*)\\)");
	private Program program;
	private BindingConsumerCollection triples;
	private OWLReasoner reasoner;
	private OWLOntologyManager ontologyManager = OWLManager.createOWLOntologyManager();
	private OWLOntology rootOntology;
	private ShortFormProvider namingStrategy;
	private OWLDataFactory dataFactory = new OWLDataFactoryImpl();

	public LinkedDataFuSpider() {
		// set logging level to warning
		Logger log = Logger.getLogger("edu.kit.aifb.datafu");
		log.setLevel(Level.OFF);
		LogManager.getLogManager().addLogger(log);
	}


	/**
	 * Some surcharges of init
	 */
	public void init() { init(null,false,null); }
	public void init(boolean withInference) { init(null,withInference,null); }
	public void init(String programFile) { init(programFile, false,null); }
	public void init(String programFile, boolean withInference) { init(programFile, withInference, null);  }
	/**
	 * Initialize the artifact by passing a program file name to the ldfu engine.
	 * Attach a reasoner to the knowledge base if <code>withInference</code> is set to true.
	 *
	 * @param programFile name of a Linked Data program file
	 * @param withInference whether a reasoner should perform inference or not
	 */

	public void init(String programFile, boolean withInference, String reasonerType) {
		if (programFile!=null) initProgram(programFile);
		initOntology(withInference, reasonerType);
	}

	private void initProgram(String programFile) {
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
		} catch (Exception e) {
			e.printStackTrace();
			program = null;
		}
	}

	/**
	 * Initialize the artifact's ontology manager.
	 */
	private void initOntology(boolean withInference, String reasonerType) {
		try {
			rootOntology = ontologyManager.createOntology();

			OWLOntologyChangeBroadcastStrategy filter = new SpecificOntologyChangeBroadcastStrategy(rootOntology);

			ObsPropertyManager m = new ObsPropertyManager();
			ontologyManager.addOntologyChangeListener(m, filter);

			OWLReasonerFactory f = null;

			// Switch to know which reasoner will be used
			if (withInference & reasonerType!=null){
				switch (reasonerType){
					case "Hermit":
						// HermiT reasoner (OWL DL)
						f = new ReasonerFactory();
						break;

					case "Pellet":
						// Pellet reasoner
						f = new PelletReasonerFactory();
						break;
					case "JFact":
						//JFact reasoner
						f = new JFactFactory();
					default:

				}
			} else if (withInference & reasonerType == null){
				// If we use reasoning, by defaut it is HermiT
				f = new ReasonerFactory();
			} else {
				// If we don't use a reasoner
				f = new StructuralReasonerFactory();
			}

			reasoner = f.createNonBufferingReasoner(rootOntology);
		} catch (OWLOntologyCreationException e) {
			e.printStackTrace();
			// TODO log
		}
		namingStrategy = NamingStrategyFactory.createDefaultNamingStrategy(ontologyManager);
	}


	/**
	 * Register an ontology declared in the document given as argument (the IRI of the ontology may differ from the IRI
	 * of the given document, e.g. if the document is a local copy of an online ontology).
	 *
	 * After successful registration, the ontology's vocabulary (class and property names) will then be used to
	 * generate unary and binary predicate in subsequent RDF crawls and the ontology's axioms will be used for
	 * automated reasoning on the crawled RDF.
	 *
	 * @param documentIRI (relative) IRI of an ontology document
	 */
	@OPERATION
	public void register(String documentIRI) {
		IRI iri = IRI.create(documentIRI);

		if (!iri.isAbsolute()) iri = Identifiers.getFileIRI(documentIRI);

		try {
			OWLOntology o = ontologyManager.contains(iri)
					// the ontology has either been manually created beforehand
					? ontologyManager.getOntology(iri)
					// or is assumed to be available online/in the file system
					: ontologyManager.loadOntologyFromOntologyDocument(iri);

			if (o.getOntologyID().isAnonymous()) {
				// set the document's IRI as ontology ID
				OWLOntologyID id = new OWLOntologyID(iri);
				ontologyManager.applyChange(new SetOntologyID(o, id));
			}

			IRI ontologyIRI = o.getOntologyID().getOntologyIRI().get();

			OWLImportsDeclaration decl = dataFactory.getOWLImportsDeclaration(ontologyIRI);
			AddImport change = new AddImport(rootOntology, decl);
			ontologyManager.applyChange(change);
		} catch (OWLOntologyCreationException e) {
			e.printStackTrace();
			failed(String.format("Couldn't register ontology <%s>", documentIRI));
			// TODO keep track of stack trace
			return;
		}
	}

	/**
	 * External action to unregister an ontology by key, it recalculate all axioms with the removed ontology and compare
	 * it to the previous ones, and removes from the observable ontology database the delta difference
	 */
	@OPERATION
	public void unregister(String documentIRI) {
		for (OWLImportsDeclaration decl : rootOntology.getImportsDeclarations()) {
			IRI ontologyIRI = decl.getIRI();

			if (documentIRI.equals(ontologyIRI.toString())) {
				RemoveImport change = new RemoveImport(rootOntology, decl);
				ontologyManager.applyChange(change);
			}

			// TODO check document IRI instead of ontology IRI
		}
	}

	/**
	 * External Action to check if the ontology is consistent (no individual instance of owl:Nothing).
	 * @param b A boolean parameter to unify with the response of the External action
	 */
	@OPERATION
	public void isConsistent(OpFeedbackParam<Boolean> b){
		if (rootOntology == null) b.set(true);
		else b.set(reasoner.isConsistent());
	}

	/**
	 * External Action to check if the ontology is satisfiable (no unsatisfiable owl class).
	 * @param b A boolean parameter to unify with the response of the External action
	 */
	@OPERATION
	public void isSatisfiable(OpFeedbackParam<Boolean> b){
		if (rootOntology == null) b.set(true);
		else b.set(reasoner.getUnsatisfiableClasses().getEntitiesMinusBottom().size() == 0);
	}


	@OPERATION
	public void crawl(String originURI) {
		crawl(originURI, null);
	}

	/**
	 * External action to execute the Linked Data program and notifies agent with collected triples and their unary
	 * binary axioms according to the already registered ontologies. Can accept local file and can have the inferred
	 * axioms (Class assertion only) of the unary/binary beliefs.
	 *
	 * @param originURI The entrypoint for the data graph file to crawl, can be a local path if the option local is activated
	 * @param http_state An optional parameter to get the http status in case of HTTP failure
	 */
	@OPERATION
	public void crawl(String originURI, OpFeedbackParam<Object> http_state) {
		if (program == null) return;

		EvaluateProgramConfig config = new EvaluateProgramConfig();
		EvaluateProgram eval = new EvaluateProgramGenerator(program, config).getEvaluateProgram();
		eval.start();

		InputOrigin origin = asOrigin(originURI);

		if (origin == null) return;

		try {
			eval.getInputOriginConsumer().consume(origin);
			eval.awaitIdleAndFinish();
			eval.shutdown();
			//TODO The http_state only works for the entrypoint File/Uri origin
			if (http_state !=null){
				if (origin instanceof RequestOrigin) http_state.set(((RequestOrigin) origin).getStatus());
				else if (origin instanceof FileOrigin) http_state.set(origin.getState().toString());
			}
		} catch (InterruptedException e) {
			e.printStackTrace();
		}

		//if (hasObsProperty("rdf")) removeObsProperty("rdf"); // TODO only if crawl succeeded FIXME does not remove properties with parameters
		// TODO clear axioms in root ontology?

		definePropertiesForBindings(this.triples.getCollection());
		addCollectionToOnt(this.triples.getCollection());
	}

	@OPERATION
	public void get(String originURI){
		get(originURI, null);
	}

	/**
	 * Performs a GET request and updates the belief base as the result.
	 * @param originURI The entrypoint for get request
	 * @param http_status An optional parameter to get the http status in case of HTTP failure
	 */
	@OPERATION
	public void get(String originURI, OpFeedbackParam<Integer> http_status) {
		InputOrigin origin = asOrigin(originURI);

		if (origin == null || !(origin instanceof RequestOrigin)) return;

		BindingConsumerCollection triples = new BindingConsumerCollection();

		EvaluateRequestOrigin eval = new EvaluateRequestOrigin();
		eval.setTripleCallback(new BindingConsumerSink(triples));
		try {
			eval.consume((RequestOrigin) origin);
			eval.shutdown();
			if (http_status !=null){
				http_status.set(((RequestOrigin) origin).getStatus());
			}
		} catch (InterruptedException e) {
			e.printStackTrace();
			if (http_status != null){
				//TODO add http status code as op feedback param
			}
			return;
		}

		// authoritative subject
		// TODO graph name available?
		if (hasObsPropertyByTemplate("rdf", originURI, null, null)) {
			removeObsPropertyByTemplate("rdf", originURI, null, null);
		}

		definePropertiesForBindings(triples.getCollection());
		addCollectionToOnt(triples.getCollection());
	}

	@OPERATION
	public void put(String originURI, Object[] payload){
		put(originURI,payload,null);
	}


	/**
	 * Performs a PUT request with the given input triples, of the form [rdf(S, P, O), rdf(S, P, O), ...].
	 *
	 * @param originURI The entrypoint for PUT request
	 * @param payload list of rdf object formed in Jason (should be of form [rdf(S, P, O), rdf(S, P, O), ...].
	 * @param http_status An optional parameter to get the http status in case of HTTP failure
	 */
	@OPERATION
	public void put(String originURI, Object[] payload, OpFeedbackParam<Integer> http_status) {
		try {
			RequestOrigin req = new RequestOrigin(new URI(originURI), Request.Method.PUT);

			Set<Nodes> triples = new HashSet<>();
			for (Object term : payload) {
				Matcher m = tripleTermPattern.matcher((String) term);
				if (m.matches()) {
					triples.add(new Nodes(asNode(m.group(1)), asNode(m.group(2)), asNode((m.group(3)))));
				}
			}
			req.setTriplesPayload(triples);

			EvaluateUnsafeRequestOrigin eval = new EvaluateUnsafeRequestOrigin();
			eval.consume(req);
			eval.shutdown();
			if (http_status !=null){
				http_status.set(req.getStatus());
			}
		} catch (InterruptedException e) {
			e.printStackTrace();
		} catch (URISyntaxException e) {
			// TODO throw it to make operation fail?
			e.printStackTrace();
		}
	}


	/**
	 * Performs a POST request with the given input triples, of the form [rdf(S, P, O), rdf(S, P, O), ...].
	 *
	 * @param originURI The entrypoint for POST request
	 * @param payload list of rdf object formed in Jason (should be of form [rdf(S, P, O), rdf(S, P, O), ...].
	 * @param http_status An optional parameter to get the http status in case of HTTP failure
	 */
	@OPERATION
	public void post(String originURI, Object[] payload, OpFeedbackParam<Integer> http_status) {
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
			if (http_status !=null){
				http_status.set(req.getStatus());
			}
			
		} catch (InterruptedException e) {
			e.printStackTrace();
		} catch (URISyntaxException e) {
			// TODO throw it to make operation fail?
			e.printStackTrace();
		}
	}

	@OPERATION
	public void delete(String originURI, Object[] payload){
		delete(originURI, payload, null);
	}

	/**
	 * Performs a DELETE request with the given input triples, of the form [rdf(S, P, O), rdf(S, P, O), ...].
	 *
	 * @param originURI The entrypoint for DELETE request
	 * @param payload list of rdf object formed in Jason (should be of form [rdf(S, P, O), rdf(S, P, O), ...].
	 * @param http_status An optional parameter to get the http status in case of HTTP failure
	 */
	@OPERATION
	public void delete(String originURI, Object[] payload, OpFeedbackParam<Integer> http_status) {
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
			if (http_status !=null){
				http_status.set(req.getStatus());
			}
		} catch (InterruptedException e) {
			e.printStackTrace();
		} catch (URISyntaxException e) {
			// TODO throw it to make operation fail?
			e.printStackTrace();
		}
	}

	/**
	 * Add Obs properties matching the passed terms
	 * @param payload list of matching terms to add
	 */
	@OPERATION
	public void addObsPropertyBinding(Object[] payload) {
		List<AddAxiom> changes = new ArrayList<>();
		for (Object term : payload) {
			Matcher m = tripleTermPattern.matcher((String) term);
			if (m.matches() && !hasObsPropertyByTemplate("rdf",m.group(1),m.group(2),m.group(3))){
				String subject = m.group(1);
				String predicate = m.group(2);
				String object = m.group(3);

				defineObsProperty("rdf", subject, predicate, object);
				Node[] t = {asNode(subject),asNode(predicate),asNode(object)};
				OWLAxiom axiom = asOwlAxiom(t);
				if (axiom != null) {
					AddAxiom addAxiom = new AddAxiom(rootOntology, axiom);
					changes.add(addAxiom);
				}
			}
		}
		if (!changes.isEmpty()) ontologyManager.applyChanges(changes);
	}

	/**
	 * Remove Obs properties matching the passed terms
	 * @param payload list of matching terms to remove
	 */
	@OPERATION
	public void removeObsPropertyBinding(Object[] payload) {
		List<RemoveAxiom> changes = new ArrayList<>();
		for (Object term : payload){
			Matcher m = tripleTermPattern.matcher((String) term);
			if (m.matches()) {
				if (hasObsPropertyByTemplate("rdf",m.group(1),m.group(2),m.group(3))){
					removeObsPropertyByTemplate("rdf",m.group(1),m.group(2),m.group(3));
					Nodes n = new Nodes(asNode(m.group(1)), asNode(m.group(2)), asNode((m.group(3))));
					OWLAxiom a = asOwlAxiom(n.getNodeArray());
					RemoveAxiom removeAxiom = new RemoveAxiom(rootOntology, a);
					changes.add(removeAxiom);
				}
			}
		}
		if (!changes.isEmpty()) ontologyManager.applyChanges(changes);
	}

	/**
	 * Remove all Obs property originated from triples
	 */
	@OPERATION
	public void removeAllObsPropertiesBinding(){
		List<RemoveAxiom> changes = new ArrayList<>();
		while (true) {
			ObsProperty o = null;
			try {
				 o = getObsProperty("rdf");
			} catch (Exception e){
				if (!changes.isEmpty()) ontologyManager.applyChanges(changes);
				return;
			}
			if (o != null && o.getValues().length == 3) {
				Nodes n = new Nodes(asNode(o.getValue(0).toString()), asNode(o.getValue(1).toString()), asNode(o.getValue(2).toString()));
				removeObsPropertyByTemplate("rdf", o.getValue(0), o.getValue(1), o.getValue(2));
				OWLAxiom a = asOwlAxiom(n.getNodeArray());
				if (a != null) {
					RemoveAxiom removeAxiom = new RemoveAxiom(rootOntology, a);
					changes.add(removeAxiom);
				}
			}
		}
	}

	private Set<ObsProperty> definePropertiesForBindings(Collection<Binding> bindings) {
		Set<ObsProperty> properties = new HashSet<>();

		for (Binding binding : bindings) {
			Node[] st = binding.getNodes().getNodeArray();

			String subject = st[0].getLabel();
			String predicate = st[1].getLabel();
			String object = st[2].getLabel();

			ObsProperty p = defineObsProperty("rdf", subject, predicate, object);
			properties.add(p);
		}
		return properties;
	}

	private ObsProperty definePropertyForAxiom(OWLAxiom axiom){
		OWLAxiomWrapper w = new OWLAxiomWrapper(axiom, namingStrategy);

		String name = w.getPropertyName();
		Object[] args = w.getPropertyArguments();
		IRI[] iris = w.getPropertyArgumentsIRI();
		ObsProperty p = null;

		if (args.length == 1) p = defineObsProperty(name, args[0]);
		else if (args.length == 2) p = defineObsProperty(name, args[0], args[1]);

		if (p != null) {
			String fullName = w.getPropertyIRI();

			if (fullName != null) {
				StringTerm t = ASSyntax.createString(fullName);
				Structure annotation = ASSyntax.createStructure(PREDICATE_IRI_FUNCTOR, t);
				p.addAnnot(annotation);
			}

			if (iris != null && iris.length == 2 && iris[0]!=null){
				StringTerm t = ASSyntax.createString(iris[0]);
				Structure annotation = ASSyntax.createStructure("s_uri", t);
				p.addAnnot(annotation);
			}

			if (iris != null && iris.length == 2 && iris[1]!=null){
				StringTerm t = ASSyntax.createString(iris[1]);
				Structure annotation = ASSyntax.createStructure("o_uri", t);
				p.addAnnot(annotation);
			}

			if (iris != null && iris.length == 1 && iris[0]!=null){
				StringTerm t = ASSyntax.createString(iris[0]);
				Structure annotation = ASSyntax.createStructure("o_uri", t);
				p.addAnnot(annotation);
			}

			return p;
		} else {
			return null;
		}

	}

	private Set<ObsProperty> definePropertiesForAxioms(Collection<OWLAxiom> axioms) {
		Set<ObsProperty> properties = new HashSet<>();
		for (OWLAxiom axiom : axioms) {
			ObsProperty p = definePropertyForAxiom(axiom);
			if (p != null ){
				properties.add(p);
			}
		}
		return properties;
	}

	private void removeProperties(Set<ObsProperty> properties) {
		for (ObsProperty p : properties) {
			String name = p.getName();
			Object[] args = p.getValues();

			if (args.length == 1) removeObsPropertyByTemplate(name, args[0]);
			else if (args.length == 2) removeObsPropertyByTemplate(name, args[0], args[1]);
		}
	}


	/**
	 * Encapsulate a file or HTTP resource into an upper-level class.
	 *
	 * @param uriOrFilename URI of the HTTP resource or file name
	 * @return an InputOrigin instance
	 */
	private InputOrigin asOrigin(String uriOrFilename) {
		if (uriOrFilename.startsWith("http")) return new RequestOrigin(URI.create(uriOrFilename), Request.Method.GET);
		else return new FileOrigin(new File(uriOrFilename), FileOrigin.Mode.READ, null);
	}

	private OWLAxiom asOwlAxiom(Node[] t) {
		if (t.length != 3) return null;
		if (t[1].getLabel().equals(RDF_TYPE)) {
			OWLClass c = dataFactory.getOWLClass(IRI.create(t[2].getLabel()));

			if (isRegistered(c)) {
				OWLNamedIndividual i = dataFactory.getOWLNamedIndividual(IRI.create(t[0].getLabel()));

				return dataFactory.getOWLClassAssertionAxiom(c, i);
			}

		} else if (t[2] instanceof Literal) {
			OWLDataProperty p = dataFactory.getOWLDataProperty(IRI.create(t[1].getLabel()));

			if (isRegistered(p)) {
				OWLNamedIndividual s = dataFactory.getOWLNamedIndividual(IRI.create(t[0].getLabel()));

				Literal lit = (Literal) t[2];

				OWLDatatype dt = null;
				if (lit.getDatatype() != null) dt = dataFactory.getOWLDatatype(IRI.create(lit.getDatatype().toString()));

				OWLLiteral o = dt != null
						? dataFactory.getOWLLiteral(lit.getLabel(), dt)
						: dataFactory.getOWLLiteral(lit.getLabel());

				return dataFactory.getOWLDataPropertyAssertionAxiom(p, s, o);
			}
		} else {
			OWLObjectProperty p = dataFactory.getOWLObjectProperty(IRI.create(t[1].getLabel()));

			if (isRegistered(p)) {

				OWLNamedIndividual s = dataFactory.getOWLNamedIndividual(IRI.create(t[0].getLabel()));
				OWLNamedIndividual o = dataFactory.getOWLNamedIndividual(IRI.create(t[2].getLabel()));

				return dataFactory.getOWLObjectPropertyAssertionAxiom(p, s, o);
			}
		}

		return null;
	}

	private boolean isRegistered(OWLEntity e) {
		OWLAxiom decl = dataFactory.getOWLDeclarationAxiom(e);
		return !ontologyManager.getOntologies(decl).isEmpty();
	}

	private void addCollectionToOnt(Collection<Binding> bindingCollection){
		List<OWLOntologyChange> changes = new ArrayList<>();
		for (Binding binding : bindingCollection) {
			Node[] st = binding.getNodes().getNodeArray();
			OWLAxiom axiom = asOwlAxiom(st);

			if (axiom != null) {
				AddAxiom addAxiom = new AddAxiom(rootOntology, axiom);
				changes.add(addAxiom);
			}
		}
		ontologyManager.applyChanges(changes);
	}

	private Object asBuiltIn(Node n) {
		if (!(n instanceof Literal)) return n.getLabel();

		Literal lit = (Literal) n;
		Resource dt = lit.getDatatype();

		if (dt == null) lit.getLabel();

		if (dt.equals(XSD.BOOLEAN)) return Boolean.parseBoolean(lit.getLabel());


		Boolean isDouble = dt.equals(XSD.DECIMAL)
				|| dt.equals(XSD.FLOAT)
				|| dt.equals(XSD.DOUBLE);

		if (isDouble) return Double.parseDouble(lit.getLabel());

		Boolean isInteger = dt.equals(XSD.INTEGER)
				|| dt.equals(XSD.INT)
				|| dt.equals(XSD.BYTE)
				|| dt.equals(XSD.SHORT)
				|| dt.equals(XSD.NEGATIVEINTEGER)
				|| dt.equals(XSD.NONNEGATIVEINTEGER)
				|| dt.equals(XSD.POSITIVEINTEGER)
				|| dt.equals(XSD.NONPOSITIVEINTEGER)
				|| dt.equals(XSD.UNSIGNEDBYTE)
				|| dt.equals(XSD.UNSIGNEDSHORT)
				|| dt.equals(XSD.UNSIGNEDINT);

		if (isInteger) return Integer.parseInt(lit.getLabel());

		return lit.getLabel();
	}

	private Node asNode(String lexicalForm) {
		if (lexicalForm.startsWith("http"))  return new Resource(lexicalForm);

		if (lexicalForm.startsWith("_:")) return new BNode((lexicalForm));

		if (lexicalForm.matches("^\\d+$")) return new Literal(lexicalForm, XSD.INTEGER);

		if (lexicalForm.matches("^\\d+\\.\\d*$")) return new Literal(lexicalForm, XSD.DOUBLE);

		if (lexicalForm.matches("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}")) return new Literal(lexicalForm, XSD.DATETIME);

		return new Literal(lexicalForm);
	}

}
