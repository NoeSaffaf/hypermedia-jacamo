package onto;


import org.semanticweb.owlapi.model.*;
import org.semanticweb.owlapi.util.OWLAxiomVisitorAdapter;
import org.semanticweb.owlapi.util.ShortFormProvider;
import tools.IRITools;

import java.util.Objects;

/**
 * Wrapper for OWL axioms to be exposed as a CArtAgO observable properties.
 * A CArtAgO property is composed of a name and an ordered list of arguments
 *
 * @author Victor Charpenay, Noe SAFFAF
 */
public class OWLAxiomWrapper {

    /**
     * Visitor that extracts a property's name and arguments from an axiom, along with the source IRI for the name
     * (to allow for disambiguation in case two IRIs map to the same short form).
     */
    protected class WrapperVisitor extends OWLAxiomVisitorAdapter {

        private String name;

        private IRI iri;

        private Object[] arguments = new Object[0];

        public String getName() {
            return name;
        }

        public IRI getIRI() {
            return iri;
        }

        public Object[] getArguments() {
            return arguments;
        }

        @Override
        public void visit(OWLDeclarationAxiom axiom) {
            OWLEntity e = axiom.getEntity();

            if (e.isOWLClass()) name = "class";
            else if (e.isOWLObjectProperty()) name = "objectProperty";
            else if (e.isOWLDataProperty()) name = "dataProperty";
            else name = null;

            if (name != null) arguments = new Object[] { e.getIRI().toString() };
        }

        @Override
        public void visit(OWLClassAssertionAxiom axiom) {
            OWLIndividual i = axiom.getIndividual();
            OWLClassExpression c = axiom.getClassExpression();

            if (i.isNamed() && !c.isAnonymous()) {
                setEntityName(c.asOWLClass());

                String indiv = namingStrategy.getShortForm(i.asOWLNamedIndividual());
                arguments = new Object[] { indiv };
            }
        }

        @Override
        public void visit(OWLObjectPropertyAssertionAxiom axiom) {
            OWLIndividual s = axiom.getSubject();
            OWLObjectPropertyExpression p = axiom.getProperty();
            OWLIndividual o = axiom.getObject();

            if (s.isNamed() && !p.isAnonymous() && o.isNamed()) {
                setEntityName(p.asOWLObjectProperty());

                String subject = namingStrategy.getShortForm(s.asOWLNamedIndividual());
                String object = namingStrategy.getShortForm(o.asOWLNamedIndividual());

                arguments = new Object[] { subject, object };
            } else {
                // TODO warn
            }
        }

        @Override
        public void visit(OWLDataPropertyAssertionAxiom axiom) {
            OWLIndividual s = axiom.getSubject();
            OWLDataPropertyExpression p = axiom.getProperty();
            OWLLiteral o = axiom.getObject();

            if (!s.isNamed() && !p.isAnonymous()) {
                setEntityName(p.asOWLDataProperty());

                String subject = namingStrategy.getShortForm(s.asOWLNamedIndividual());
                String object = o.getLiteral(); // TODO language string and datatype as annotations?

                arguments = new Object[] { subject, object };
            } else {
                // TODO warn
            }
        }

        private void setEntityName(OWLEntity e) {
            iri = e.getIRI();
            name = IRITools.getJasonAtomIdentifier(namingStrategy.getShortForm(e));
        }

    }

    protected OWLAxiom axiom;

    protected ShortFormProvider namingStrategy;

    protected final WrapperVisitor visitor = new WrapperVisitor();

    public OWLAxiomWrapper(OWLAxiom axiom, ShortFormProvider namingStrategy) {
        this.axiom = axiom;
        this.namingStrategy = namingStrategy;

        axiom.accept(visitor);
    }

    /**
     * Returns the property's shortened name based on naming strategies
     * @return Shortened Name of an OWL Axiom
     */
    public String getPropertyName() {
        return visitor.getName();
    }

    /**
     * Returns the property's name of an axiom
     * @return Name of an OWL Axiom
     */
    public String getPropertyIRI() {
        IRI iri = visitor.getIRI();
        return iri != null ? iri.toString() : null;
    }

    /**
     * Returns the property's arguments
     * @return Property's arguments
     */
    public Object[] getPropertyArguments() {
        return visitor.getArguments();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof OWLAxiomWrapper)) return false;
        OWLAxiomWrapper that = (OWLAxiomWrapper) o;
        return axiom.equals(that.axiom);
    }

    @Override
    public int hashCode() {
        return Objects.hash(axiom, namingStrategy);
    }

}
