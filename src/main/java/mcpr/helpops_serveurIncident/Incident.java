package mcpr.helpops_serveurIncident;

import java.util.Date;

public class Incident {

    private final int identifiant;
    private String titre;
    private String categorie;
    private String description;
    private final String etat;
    private final Date dateCreation;
    private final String identifiantCreateur;

    // état de base d'un incident (simplifie les conditions avec une constante)
    public static final String OPEN = "OPEN";

    public Incident(int identifiant, String titre, String categorie, String description, String identifiantCreateur) {
        this.identifiant = identifiant;
        this.categorie = categorie;
        this.titre = titre;
        this.description = description;
        this.identifiantCreateur = identifiantCreateur;
        this.etat = OPEN;
        this.dateCreation = new Date();
    }

    @Override
    public String toString() {
        return new StringBuilder()
                .append("Ticket #").append(identifiant)
                .append(" [").append(etat).append("]")
                .append(" - ").append(titre)
                .append(" (").append(categorie).append(")").append(dateCreation)
                .toString();
    }

    // méthodes getters
    public int getIdentifiant() {
        return identifiant;
    }

    public String getTitre() {
        return titre;
    }

    public String getCategorie() { return categorie; }

    public String getDescription() {
        return description;
    }

    public String getEtat() {
        return etat;
    }

    public Date getDateCreation() {
        return dateCreation;
    }

    public String getIdentifiantCreateur() {
        return identifiantCreateur;
    }

    // Setters pour la modif du ticket
    public void setTitre(String titre) {
        this.titre = titre;
    }

    public void setCategorie(String categorie) {
        this.categorie = categorie;
    }

    public void setDescription(String description) {
        this.description = description;
    }
}