package models;

import javax.persistence.*;

import com.vividsolutions.jts.geom.Geometry;
import com.vividsolutions.jts.geom.GeometryFactory;
import com.vividsolutions.jts.operation.overlay.OverlayOp;

import java.math.BigInteger;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import play.db.jpa.*;
import play.data.validation.*;
import utils.GeometryUtils;

@Entity
public class NtdAgency extends Model {
    
    /** Human-readable agency name */
    @Required
    public String name;

    /** This agency's primary location on the WWW */
    @Required
    @URL
    public String url;

    /** 
     * This agency's ID in the National Transit Database. Stored as string to preserve
     * leading zeros.
     */
    public String ntdId;

    /** This agency's UZA name(s) in the National Transit Database */
    @ElementCollection
    public List<String> uzaNames;

    /** Service area population */
    public int population;

    /** Annual unlinked passenger trips */
    public int ridership;
    
    /** Annual passenger miles */
    public int passengerMiles;
    
    /** Where the data for this agency came from */
    @Enumerated(EnumType.STRING)
    public AgencySource source;
    
    /**
     * Machine readable problem type requiring human review - picked up in the admin interface.
     */
    @Enumerated(EnumType.STRING)
    public ReviewType review;

    /** Does this agency provide GTFS to Google? */
    public boolean googleGtfs;

    /** A note for human review */
    public String note;

    /** The metro for this agency. Since agencies and metros now have a many-to-many relationship, this is deprecated */
    @ManyToOne
    @Deprecated
    public MetroArea metroArea;

    /**
     * A list of metro areas that contain this agency.
     */
    public List<MetroArea> getMetroAreas () {
        return MetroArea.find("SELECT m FROM MetroArea m INNER JOIN m.agencies agencies WHERE ? in agencies",
                    this).fetch();  
    }
    
    @ManyToMany(cascade=CascadeType.PERSIST)
    public Set<GtfsFeed> feeds;

    /**
     * Is this agency disabled?
     */
    public boolean disabled;

    /** 
     * Convert to a human-readable string. This is exposed in the admin interface, so it should be
     * correct.
     */
    public String toString () {
        if (name != null && !name.equals(""))
            return name;
        else
            return url;
    }

    // TODO: argumented constructors
    public NtdAgency () {
        this(null, null, null, 0, null, 0, 0);
    }

    public NtdAgency (String name, String url, String ntdId, int population,
                      List<String> uzaNames, int ridership, int passengerMiles) {
        this.name = name;
        this.url = url;
        this.ntdId = ntdId;
        this.population = population;
        this.uzaNames = uzaNames;
        this.ridership = ridership;
        this.passengerMiles = passengerMiles;
        this.source = AgencySource.NTD;
        this.note = null;
        this.disabled = false;
        feeds = new HashSet<GtfsFeed>();
    }

    /**
     * Build an NTD agency based on information in a GTFS feed.
     * @param feed
     */
    public NtdAgency(GtfsFeed feed) {
        this.name = feed.agencyName;
        this.url = feed.agencyUrl;
        this.note = null;
        this.ntdId = null;
        this.population = 0;
        this.ridership = 0;
        this.passengerMiles = 0;
        this.disabled = feed.disabled;
        this.source = AgencySource.GTFS;
        feeds = new HashSet<GtfsFeed>();
    }

    public Geometry getGeom() {
        Geometry out = null;
        Integer srid = null;
        
        for (GtfsFeed feed : feeds) {
            // ignore feeds that did not parse as they will have null geoms
            if (feed.status != FeedParseStatus.SUCCESSFUL)
                continue;
            
            if (srid == null)
                srid = feed.the_geom.getSRID();
            
            if (out == null)
                out = feed.the_geom;
            else
                out = OverlayOp.overlayOp(out, feed.the_geom, OverlayOp.UNION);
        }
        
        // re-set SRID, it gets lost
        if (out != null)
            out.setSRID(srid);
        
        return out;
    }

    /**
     * Make this agency a member of every metro it overlaps, without merging anything.
     */
    public void splitToAreas() {
        Geometry agencyGeom = this.getGeom();

        String query = "SELECT m.id FROM MetroArea m WHERE " + 
                "ST_DWithin(m.the_geom, transform(ST_GeomFromText(?, ?), ST_SRID(m.the_geom)), 0.04)";;
        Query ids = JPA.em().createNativeQuery(query);
        ids.setParameter(1, agencyGeom.toText());
        ids.setParameter(2, agencyGeom.getSRID());
        List<BigInteger> metrosTemp = ids.getResultList();
        
        MetroArea metro;
        for (BigInteger metroId : metrosTemp) {
            metro = MetroArea.findById(metroId.longValue());
            metro.agencies.add(this);
            metro.save();
        }
    }

    /**
     * Merge all the areas this agency is potentially a part of. Beware this will create huge agencies if
     * it is applied to (a) something like Amtrak or Greyhound or (b) an agency with a few misplaced stops
     * far away in other metros; since geoms are convex-hulled, they will cross lots of areas.
     */
    public void mergeAllAreas() {
        Geometry agencyGeom = this.getGeom();

        String query = "SELECT m.id FROM MetroArea m WHERE " + 
                "ST_DWithin(m.the_geom, transform(ST_GeomFromText(?, ?), ST_SRID(m.the_geom)), 0.04)";;
        Query ids = JPA.em().createNativeQuery(query);
        ids.setParameter(1, agencyGeom.toText());
        ids.setParameter(2, agencyGeom.getSRID());
        List<BigInteger> metrosTemp = ids.getResultList();
        
        MetroArea metro;
        MetroArea first = MetroArea.findById(metrosTemp.get(0).longValue());
        metrosTemp.remove(0);
        
        for (BigInteger metroId : metrosTemp) {
            metro = MetroArea.findById(metroId.longValue());
            first.mergeAreas(metro);
            metro.delete();
        }
        
        first.agencies.add(this);
        first.save();
    }      
}
