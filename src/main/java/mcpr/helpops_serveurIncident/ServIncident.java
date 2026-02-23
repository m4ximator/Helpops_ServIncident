package mcpr.helpops_serveurIncident;

import mcpr.hellpops_interfaces.IAuthService;
import mcpr.hellpops_interfaces.ITicketService;
import mcpr.hellpops_interfaces.Jeton;

import java.rmi.Naming;
import java.rmi.RemoteException;
import java.rmi.server.UnicastRemoteObject;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

public class ServIncident extends UnicastRemoteObject implements ITicketService{

    private IAuthService auth;

    // Base de données thread-safe pour les incidents
    private final List<Incident> incidentEnBase = new CopyOnWriteArrayList<>();

    // Compteur thread-safe pour générer les identifiants
    private final AtomicInteger compteurId = new AtomicInteger(0);

    public ServIncident() throws RemoteException {
        super();
        try {
            auth = (IAuthService) Naming.lookup("rmi://localhodt:1099/AuthService");

        }catch (Exception e){
            System.err.println("Erreur critique : serveur Auth inateignable.");
        }
    }

    @Override
    public String creerIncident(Jeton jeton, String categorie, String titre, String desc) throws RemoteException {
        // Demande au serveur Auth à qui appartient le jeton
        String loginCreateur = auth.getLoginParJeton(jeton);

        // Si login différent de null, jeton valide
        if (loginCreateur != null){
            int id = compteurId.incrementAndGet();
            Incident incident = new Incident(id, titre, categorie, desc, loginCreateur);
            incidentEnBase.add(incident);
            StringBuilder chaine = new StringBuilder();
            chaine.append("Ticket #").append(id).append(" créé par ").append(loginCreateur);
            System.out.println(chaine.toString());
            return chaine.toString();
        }
        return "Session Expirée";
    }

    @Override
    public List<Incident> consulterListeIncident(Jeton jeton) throws RemoteException {
        String loginDemandeur = auth.getLoginParJeton(jeton);

        if (loginDemandeur != null) {
            //liste vide pour les tickets du client
            List<Incident> ticketsDuClient = new ArrayList<>();

            // Parcours de la liste globale
            for (Incident incident : incidentEnBase) {
                if (incident.getIdentifiantCreateur().equals(loginDemandeur)) {
                    ticketsDuClient.add(incident);
                }
            }
            return ticketsDuClient; //liste filtrée
        }
        return null; // Jeton invalide
    }
    
    
    //Fonction permettant de consulter les détails d'un ticket, en vérifiant que le jeton est valide
    @Override
    public Incident consulterIncidentDetail(Jeton jeton, int id) throws RemoteException {
        String loginDemandeur = auth.getLoginParJeton(jeton);

        if (loginDemandeur != null) {
			for (Incident incident : incidentEnBase) {
				if (incident.getIdentifiant() == id && incident.getIdentifiantCreateur().equals(loginDemandeur)) {
					return incident;
				}
			}
		}
		return null;
	}

}
