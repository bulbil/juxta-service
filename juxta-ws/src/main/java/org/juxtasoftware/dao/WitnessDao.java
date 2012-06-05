package org.juxtasoftware.dao;

import java.io.Reader;
import java.util.List;
import java.util.Set;

import org.juxtasoftware.model.ComparisonSet;
import org.juxtasoftware.model.RevisionInfo;
import org.juxtasoftware.model.Usage;
import org.juxtasoftware.model.Witness;
import org.juxtasoftware.model.Workspace;

import eu.interedition.text.Text;

/**
 * Witness DAO - lookup witness data by set or name. Access to content stream
 */
public interface WitnessDao extends JuxtaDao<Witness> {
    
    /**
     * Update the witness content
     * 
     * @param witnes
     * @param newContent
     */
    void updateContent(final Witness witnes, final Text newContent );
    
    /**
     * Find all of the witnesses associated with the set
     * @param set
     * @return
     */
    Set<Witness> find(final ComparisonSet set);
    
    /**
     * Rename an existing witness
     * @param witness
     * @param newName
     */
    void rename(final Witness witness, final String newName );
    
    /**
     * Get a content reader for the witness
     * @param witness
     * @return
     */
    Reader getContentStream( final Witness witness );
    
    /**
     * Find a witness in a comparison set by name
     * @param set
     * @param title
     * @return
     */
    Witness find( final ComparisonSet set, final String title);
    
    /**
     * List all witnesses in a workspace
     * @param ws
     * @return
     */
    List<Witness> list( final Workspace ws);
    
    /**
     * Check if a witness exists in the workspace
     * 
     * @param ws
     * @param title
     * @return
     */
    boolean exists( final Workspace ws, final String title);
    
    /**
     * Find a witness by workspace and title
     * @param ws
     * @param setName
     * @return
     */
    Witness find( final Workspace ws, final String setName );
    
    /**
     * Get a list of usage information for this witness. The list
     * details all of the witnesses based upon this source, and
     * all of the sets using these witnesses
     * @param src
     * @return
     */
    List<Usage> getUsage( final Witness witness );
    
    /**
     * Add a set of revisons to this witness
     * @param revs
     */
    void addRevisions( List<RevisionInfo> revs);

    /**
     * Return true if the specified witness has revision sites included
     * @param base
     * @return
     */
    boolean hasRevisions(Witness witness);
    
    /**
     * Get a list of revisions that are included in this witness
     */
    List<RevisionInfo> getRevisions( Witness witness) ;
    
    /**
     * Remove all revision info for the specified witness
     * @param witness
     */
    public void clearRevisions(Witness witness);
}
