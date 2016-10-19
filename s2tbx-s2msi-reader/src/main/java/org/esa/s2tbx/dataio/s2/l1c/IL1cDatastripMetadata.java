package org.esa.s2tbx.dataio.s2.l1c;

import org.esa.s2tbx.dataio.s2.filepatterns.S2FileNamingItems;
import org.esa.snap.core.datamodel.MetadataElement;

/**
 * Created by obarrile on 30/09/2016.
 */
public interface IL1cDatastripMetadata {
    MetadataElement getMetadataElement();
    void updateNamingItems(S2FileNamingItems namingItems);
}
