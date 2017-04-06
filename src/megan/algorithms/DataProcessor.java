/*
 *  Copyright (C) 2017 Daniel H. Huson
 *
 *  (Some files contain contributions from other authors, who are then mentioned separately.)
 *
 *  This program is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package megan.algorithms;

import jloda.util.*;
import megan.classification.Classification;
import megan.classification.ClassificationManager;
import megan.classification.IdMapper;
import megan.core.Document;
import megan.core.SyncArchiveAndDataTable;
import megan.data.*;
import megan.fx.NotificationsInSwing;
import megan.io.InputOutputReaderWriter;
import megan.rma6.RMA6File;
import megan.rma6.ReadBlockRMA6;
import megan.util.interval.Interval;
import megan.util.interval.IntervalTree;

import java.io.IOException;
import java.util.*;

/**
 * Analyzes all reads in a sample
 * Daniel Huson, 1.2009, 3.2106
 */
public class DataProcessor {
    /**
     * process a dataset
     *
     * @param doc
     * @throws jloda.util.CanceledException
     */
    public static int apply(final Document doc) throws CanceledException {
        final ProgressListener progress = doc.getProgressListener();
        try {
            progress.setTasks("Analyzing reads & alignments", "Initialization");

            System.err.println("Analyzing...");
            if (doc.isUseIdentityFilter()) {
                System.err.println("Using min percent-identity values for taxonomic assignment of 16S reads");
            }

            final int numberOfClassifications = doc.getActiveViewers().size();
            final String[] cNames = doc.getActiveViewers().toArray(new String[numberOfClassifications]);
            final int taxonomyIndex = Basic.getIndex(Classification.Taxonomy, cNames);
            for (int i = 0; i < cNames.length; i++) {
                if (i != taxonomyIndex)
                    ClassificationManager.ensureTreeIsLoaded(cNames[i]);
            }

            final UpdateItemList updateList = new UpdateItemList(cNames.length);

            final boolean doMatePairs = doc.isPairedReads() && doc.getMeganFile().isRMA6File();

            if (doc.isPairedReads() && !doc.getMeganFile().isRMA6File())
                System.err.println("WARNING: Not an RMA6 file, will ignore paired read information");
            if (doMatePairs)
                System.err.println("Using paired reads in taxonomic assignment...");

            // step 0: set up classification algorithms

            final boolean usingMultiGeneAnalysis = (doc.getLcaAlgorithm() == Document.LCAAlgorithm.NaiveMultiGene);

            final double minCoveredPercent = Math.min(100, ProgramProperties.get("minCoveredPercent", 0.0));
            int numberOfReadsFailedCoveredThreshold = 0;
            final IntervalTree<Object> intervals;
            if (minCoveredPercent > 0) {
                System.err.println(String.format("Minimum percentage of read to be covered: %.1f%%", minCoveredPercent));
                if (doc.isLongReads())
                    intervals = new IntervalTree<>();
                else
                    intervals = null;
            } else
                intervals = null;

            final IAssignmentAlgorithmCreator[] assignmentAlgorithmCreators = new IAssignmentAlgorithmCreator[numberOfClassifications];
            for (int i = 0; i < numberOfClassifications; i++) {
                if (i == taxonomyIndex) {
                    switch (doc.getLcaAlgorithm()) {
                        case NaiveMultiGene:
                            assignmentAlgorithmCreators[i] = new AssignmentUsingMultiGeneLCACreator(cNames[taxonomyIndex], doc.isUseIdentityFilter(), doc.getTopPercent());
                            break;
                        case Weighted:
                            assignmentAlgorithmCreators[i] = new AssignmentUsingWeightedLCACreator(doc, cNames[taxonomyIndex], doc.isUseIdentityFilter(), doc.getWeightedLCAPercent());
                            break;
                        default:
                        case Naive:
                            assignmentAlgorithmCreators[i] = new AssignmentUsingLCAForTaxonomyCreator(cNames[i], doc.isUseIdentityFilter());
                    }
                } else if (ProgramProperties.get(cNames[i] + "UseLCA", false))
                    assignmentAlgorithmCreators[i] = new AssignmentUsingLCACreator(cNames[i]);
                else if (usingMultiGeneAnalysis)
                    assignmentAlgorithmCreators[i] = new AssignmentUsingMultiGeneBestHitCreator(cNames[i], doc.getMeganFile().getFileName());
                else
                    assignmentAlgorithmCreators[i] = new AssignmentUsingBestHitCreator(cNames[i], doc.getMeganFile().getFileName());
            }

            // step 1:  stream through reads and assign classes

            progress.setSubtask("Processing alignments");

            long numberOfReadsFound = 0;
            double totalWeight = 0;
            long numberOfMatches = 0;
            long numberOfReadsWithLowComplexity = 0;
            long numberOfReadsWithHits = 0;
            long numberAssignedViaMatePair = 0;

            final int[] countUnassigned = new int[numberOfClassifications];
            final int[] countAssigned = new int[numberOfClassifications];

            final IAssignmentAlgorithm[] assignmentAlgorithm = new IAssignmentAlgorithm[numberOfClassifications];
            for (int i = 0; i < numberOfClassifications; i++)
                assignmentAlgorithm[i] = assignmentAlgorithmCreators[i].createAssignmentAlgorithm();

            final Set<Integer>[] knownIds = new HashSet[numberOfClassifications];
            for (int i = 0; i < cNames.length; i++) {
                knownIds[i] = new HashSet<>();
                knownIds[i].addAll(ClassificationManager.get(cNames[i], true).getName2IdMap().getIds());
            }

            final IConnector connector = doc.getConnector();
            final InputOutputReaderWriter mateReader = doMatePairs ? new InputOutputReaderWriter(doc.getMeganFile().getFileName(), "r") : null;

            final float topPercent = (usingMultiGeneAnalysis ? 100 : doc.getTopPercent()); // if we are using the long-read lca, must not use this filter on original matches

            final int[] classIds = new int[numberOfClassifications];
            final ArrayList<int[]>[] moreClassIds;
            final float[] multiGeneWeights;
            if (usingMultiGeneAnalysis) {
                moreClassIds = new ArrayList[numberOfClassifications];
                for (int i = 0; i < moreClassIds.length; i++)
                    moreClassIds[i] = new ArrayList<>();
                multiGeneWeights = new float[numberOfClassifications];
            } else {
                moreClassIds = null;
                multiGeneWeights = null;
            }

            final BitSet activeMatches = new BitSet(); // pre filter matches for taxon identification
            final BitSet activeMatchesForMateTaxa = new BitSet(); // pre filter matches for mate-based taxon identification

            try (final IReadBlockIterator it = connector.getAllReadsIterator(0, 10, false, true)) {
                progress.setMaximum(it.getMaximumProgress());
                progress.setProgress(0);

                final ReadBlockRMA6 mateReadBlock;
                if (doMatePairs) {
                    try (RMA6File RMA6File = new RMA6File(doc.getMeganFile().getFileName(), "r")) {
                        String[] matchClassificationNames = RMA6File.getHeaderSectionRMA6().getMatchClassNames();
                        mateReadBlock = new ReadBlockRMA6(doc.getBlastMode(), true, matchClassificationNames);
                    }
                } else
                    mateReadBlock = null;

                while (it.hasNext()) {
                    // clean up previous values
                    for (int i = 0; i < numberOfClassifications; i++) {
                        classIds[i] = 0;
                        if (usingMultiGeneAnalysis) {
                            moreClassIds[i].clear();
                            multiGeneWeights[i] = 0;
                        }
                    }

                    final IReadBlock readBlock = it.next();

                    if (progress.isUserCancelled())
                        break;

                    if (readBlock.getReadWeight() == 0)
                        readBlock.setReadWeight(1);
                    if (doc.isLongReads())
                        readBlock.setReadWeight(readBlock.getReadWeight() * readBlock.getReadLength());

                    numberOfReadsFound++;
                    totalWeight += readBlock.getReadWeight();
                    numberOfMatches += readBlock.getNumberOfMatches();

                    final boolean hasLowComplexity = readBlock.getComplexity() > 0 && readBlock.getComplexity() + 0.01 < doc.getMinComplexity();

                    if (hasLowComplexity)
                        numberOfReadsWithLowComplexity += readBlock.getReadWeight();

                    ActiveMatches.compute(doc.getMinScore(), topPercent, doc.getMaxExpected(), doc.getMinPercentIdentity(), readBlock, Classification.Taxonomy, activeMatches);

                    int taxId = 0;
                    if (taxonomyIndex >= 0) {
                        if (minCoveredPercent == 0 || ensureCovered(minCoveredPercent, readBlock, activeMatches, intervals)) {
                            if (doMatePairs && readBlock.getMateUId() > 0) {
                                mateReader.seek(readBlock.getMateUId());
                                mateReadBlock.read(mateReader, false, true, doc.getMinScore(), doc.getMaxExpected());
                                taxId = assignmentAlgorithm[taxonomyIndex].computeId(activeMatches, readBlock);
                                ActiveMatches.compute(doc.getMinScore(), topPercent, doc.getMaxExpected(), doc.getMinPercentIdentity(), mateReadBlock, Classification.Taxonomy, activeMatchesForMateTaxa);
                                int mateTaxId = assignmentAlgorithm[taxonomyIndex].computeId(activeMatchesForMateTaxa, mateReadBlock);
                                if (mateTaxId > 0) {
                                    if (taxId <= 0) {
                                        taxId = mateTaxId;
                                        numberAssignedViaMatePair++;
                                    } else {
                                        int bothId = assignmentAlgorithm[taxonomyIndex].getLCA(taxId, mateTaxId);
                                        if (bothId == taxId)
                                            taxId = mateTaxId;
                                            // else if(bothId==taxId) taxId=taxId; // i.e, no change
                                        else if (bothId != mateTaxId)
                                            taxId = bothId;
                                    }
                                }
                            } else {
                                taxId = assignmentAlgorithm[taxonomyIndex].computeId(activeMatches, readBlock);
                            }
                        } else
                            numberOfReadsFailedCoveredThreshold++;
                    }

                    if (activeMatches.cardinality() > 0)
                        numberOfReadsWithHits += readBlock.getReadWeight();

                    for (int i = 0; i < numberOfClassifications; i++) {
                        int id;
                        if (hasLowComplexity) {
                            id = IdMapper.LOW_COMPLEXITY_ID;
                        } else if (i == taxonomyIndex) {
                            id = taxId;
                        } else {
                            ActiveMatches.compute(doc.getMinScore(), topPercent, doc.getMaxExpected(), doc.getMinPercentIdentity(), readBlock, cNames[i], activeMatches);
                            id = assignmentAlgorithm[i].computeId(activeMatches, readBlock);
                            if (id > 0 && usingMultiGeneAnalysis && assignmentAlgorithm[i] instanceof IMultiAssignmentAlgorithm) {
                                int numberOfSegments = ((IMultiAssignmentAlgorithm) assignmentAlgorithm[i]).getOtherClassIds(i, numberOfClassifications, moreClassIds[i]);
                                multiGeneWeights[i] = (numberOfSegments > 0 ? (float) readBlock.getReadWeight() / (float) numberOfSegments : 0);
                            }
                        }
                        if (!knownIds[i].contains(id))
                            id = IdMapper.UNASSIGNED_ID;

                        classIds[i] = id;
                        if (id == IdMapper.UNASSIGNED_ID)
                            countUnassigned[i]++;

                        else if (id > 0)
                            countAssigned[i]++;
                    }
                    updateList.addItem(readBlock.getUId(), readBlock.getReadWeight(), classIds);

                    if (usingMultiGeneAnalysis) {
                        for (int i = 0; i < numberOfClassifications; i++) {
                            for (int[] aClassIds : moreClassIds[i]) {
                                updateList.addItem(readBlock.getUId(), multiGeneWeights[i], aClassIds);
                            }
                        }
                    }

                    progress.setProgress(it.getProgress());
                }
            } catch (Exception ex) {
                Basic.caught(ex);
            } finally {
                if (mateReader != null)
                    mateReader.close();
            }

            if (progress.isUserCancelled())
                throw new CanceledException();

            if (progress instanceof ProgressPercentage) {
                ((ProgressPercentage) progress).reportTaskCompleted();
            }

            System.err.println(String.format("Total reads:  %,15d", numberOfReadsFound));
            if (totalWeight > numberOfReadsFound)
                System.err.println(String.format("Total weight: %,15d", (long) totalWeight));

            if (numberOfReadsWithLowComplexity > 0)
                System.err.println(String.format("Low complexity:%,15d", numberOfReadsWithLowComplexity));
            if (numberOfReadsFailedCoveredThreshold > 0)
                System.err.println(String.format("Low covered:   %,15d", numberOfReadsFailedCoveredThreshold));

            System.err.println(String.format("With hits:     %,15d ", numberOfReadsWithHits));
            System.err.println(String.format("Alignments:    %,15d", numberOfMatches));

            for (int i = 0; i < countAssigned.length; i++) {
                System.err.println(String.format("%-19s%,11d", "Assig. " + cNames[i] + ":", countAssigned[i]));
            }

            // if used mate pairs, report here:
            if (numberAssignedViaMatePair > 0) {
                System.err.println(String.format("Tax. ass. by mate:%,12d", numberAssignedViaMatePair));
            }

            progress.setCancelable(false); // can't cancel beyond here because file could be left in undefined state

            doc.setNumberReads(Math.round(totalWeight));

            // If min support percentage is set, set the min support:
            if (doc.getMinSupportPercent() > 0) {
                doc.setMinSupport((int) Math.max(1, (doc.getMinSupportPercent() / 100.0) * (numberOfReadsWithHits + numberAssignedViaMatePair)));
                System.err.println("MinSupport set to: " + doc.getMinSupport());
            }

            // 2. apply min support and disabled taxa filter

            for (int i = 0; i < numberOfClassifications; i++) {
                final String cName = cNames[i];
                // todo: need to remove assignments to disabled ids when not using the LCA algorithm
                if (ProgramProperties.get(cName + "UseLCA", cName.equals(Classification.Taxonomy))
                        && (doc.getMinSupport() > 0 || ClassificationManager.get(cName, false).getIdMapper().getDisabledIds().size() > 0)) {
                    progress.setSubtask("Applying min-support & disabled filter to " + cName + "...");
                    final MinSupportFilter minSupportFilter = new MinSupportFilter(cName, updateList.getClassIdToWeightMap(i), doc.getMinSupport(), progress);
                    final Map<Integer, Integer> changes = minSupportFilter.apply();

                    for (Integer srcId : changes.keySet()) {
                        updateList.appendClass(i, srcId, changes.get(srcId));
                    }
                    System.err.println(String.format("Min-supp. changes:%,12d", changes.size()));
                }
            }

            // 3. save classifications

            doc.getProgressListener().setSubtask("Writing classification tables");

            connector.updateClassifications(cNames, updateList, progress);
            connector.setNumberOfReads((int) doc.getNumberOfReads());

            // 4. sync
            progress.setSubtask("Syncing");
            SyncArchiveAndDataTable.syncRecomputedArchive2Summary(doc.isUseWeightedReadCounts(), doc.getTitle(), "LCA", doc.getBlastMode(), doc.getParameterString(), connector, doc.getDataTable(), (int) doc.getAdditionalReads());

            if (progress instanceof ProgressPercentage)
                ((ProgressPercentage) progress).reportTaskCompleted();

            // MeganProperties.addRecentFile(new File(doc.getMeganFile().getFileName()));
            doc.setDirty(false);

            // report classification sizes:
            for (String cName : cNames) {
                System.err.println(String.format("Class. %-13s%,10d", cName + ":", connector.getClassificationSize(cName)));
            }

            return (int) doc.getDataTable().getTotalReads();
        } catch (IOException ex) {
            Basic.caught(ex);
            NotificationsInSwing.showInternalError("Data Processor failed: " + ex.getMessage());
        }
        return 0;
    }

    /**
     * check that enough of read is covered by alignments
     *
     * @param minCoveredPercent percent of read that must be covered
     * @param readBlock
     * @param activeMatches
     * @param intervals         this will non-null in long read mode, in which case we check the total cover, otherwise, we check the amount covered by any one match
     * @return true, if sufficient coverage
     */
    private static boolean ensureCovered(double minCoveredPercent, IReadBlock readBlock, BitSet activeMatches, IntervalTree<Object> intervals) {
        int lengthToCover = (int) (0.01 * minCoveredPercent * readBlock.getReadLength());
        if (lengthToCover == 0)
            return true;

        if (intervals != null)
            intervals.clear();

        for (int m = activeMatches.nextSetBit(0); m != -1; m = activeMatches.nextSetBit(m + 1)) {
            final IMatchBlock matchBlock = readBlock.getMatchBlock(m);
            if (Math.abs(matchBlock.getAlignedQueryStart() - matchBlock.getAlignedQueryStart()) >= lengthToCover)
                return true;
            if (intervals != null) {
                Interval<Object> interval = new Interval<>(matchBlock.getAlignedQueryStart(), matchBlock.getAlignedQueryEnd(), null);
                intervals.add(interval);
                if (intervals.computeCovered() >= lengthToCover)
                    return true;
            }
        }
        return false;
    }
}
