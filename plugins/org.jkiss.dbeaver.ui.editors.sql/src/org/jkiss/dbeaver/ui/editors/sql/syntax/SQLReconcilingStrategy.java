/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2020 DBeaver Corp and others
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jkiss.dbeaver.ui.editors.sql.syntax;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.jface.text.*;
import org.eclipse.jface.text.reconciler.DirtyRegion;
import org.eclipse.jface.text.reconciler.IReconcilingStrategy;
import org.eclipse.jface.text.reconciler.IReconcilingStrategyExtension;
import org.eclipse.jface.text.source.Annotation;
import org.eclipse.jface.text.source.projection.ProjectionAnnotation;
import org.eclipse.jface.text.source.projection.ProjectionAnnotationModel;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.model.sql.SQLScriptElement;
import org.jkiss.dbeaver.ui.editors.sql.SQLEditorBase;

import java.util.*;
import java.util.stream.Collectors;

public class SQLReconcilingStrategy implements IReconcilingStrategy, IReconcilingStrategyExtension {
    private final NavigableSet<SQLScriptElementImpl> cache = new TreeSet<>();

    private final SQLEditorBase editor;

    private IDocument document;

    public SQLReconcilingStrategy(SQLEditorBase editor) {
        this.editor = editor;
    }

    @Override
    public void setDocument(IDocument document) {
        this.document = document;
    }

    @Override
    public void setProgressMonitor(IProgressMonitor monitor) {
        //todo use monitor
    }

    @Override
    public void reconcile(DirtyRegion dirtyRegion, IRegion subRegion) {
        if (DirtyRegion.INSERT.equals(dirtyRegion.getType())) {
            reconcile(subRegion.getOffset(), subRegion.getLength());
        } else {
            reconcile(subRegion.getOffset(), 0);
        }
    }

    @Override
    public void reconcile(IRegion partition) {
        reconcile(partition.getOffset(), partition.getLength());
    }

    @Override
    public void initialReconcile() {
        reconcile(0, document.getLength());
    }

    private void reconcile(int damagedRegionOffset, int damagedRegionLength) {
        if (!editor.isFoldingEnabled()) {
            return;
        }
        ProjectionAnnotationModel model = editor.getAnnotationModel();
        if (model == null) {
            return;
        }

        SQLScriptElementImpl leftBound = cache.lower(new SQLScriptElementImpl(damagedRegionOffset, damagedRegionLength));
        if (leftBound != null) {
            leftBound = cache.lower(leftBound);
        }
        SQLScriptElementImpl rightBound = cache.ceiling(new SQLScriptElementImpl(damagedRegionOffset + damagedRegionLength, 0));
        if (leftBound == null) {
            damagedRegionOffset = 0;
        } else {
            damagedRegionOffset = leftBound.getOffset() + leftBound.getLength();
        }
        if (rightBound == null) {
            damagedRegionLength = document.getLength();
        } else {
            damagedRegionLength = rightBound.getOffset() + rightBound.getLength() - damagedRegionOffset;
        }

        List<SQLScriptElement> parsedQueries = extractQueries(damagedRegionOffset, damagedRegionLength);
        if (parsedQueries == null) {
            return;
        }

        if (rightBound != null && !parsedQueries.isEmpty()) {
            SQLScriptElement rightmostParsedQuery = parsedQueries.get(parsedQueries.size() - 1);
            if (!rightBound.equals(new SQLScriptElementImpl(rightmostParsedQuery.getOffset(), expandQueryLength(rightmostParsedQuery)))) {
                parsedQueries = extractQueries(damagedRegionOffset, document.getLength());
                if (parsedQueries == null) {
                    return;
                }
                rightBound = null;
            }
        }

        Collection<SQLScriptElementImpl> cachedQueries;
        if (leftBound == null && rightBound == null) {
            cachedQueries = Collections.unmodifiableNavigableSet(cache);
        } else if (leftBound == null) {
            cachedQueries = Collections.unmodifiableNavigableSet(cache.headSet(rightBound, true));
        } else if (rightBound == null) {
            cachedQueries = Collections.unmodifiableNavigableSet(cache.tailSet(leftBound, false));
        } else {
            cachedQueries = Collections.unmodifiableNavigableSet(cache.subSet(leftBound, false, rightBound, true));
        }

        List<SQLScriptElementImpl> parsedElements = parsedQueries.stream()
                .filter(this::deservesFolding)
                .map(element -> new SQLScriptElementImpl(element.getOffset(), expandQueryLength(element)))
                .collect(Collectors.toList());
        Map<Annotation, SQLScriptElementImpl> additions = new HashMap<>();
        for (SQLScriptElementImpl element: parsedElements) {
            if (!cachedQueries.contains(element)) {
                element.setAnnotation(new ProjectionAnnotation());
                additions.put(element.getAnnotation(), element);
            }
        }
        Collection<SQLScriptElementImpl> deletedPositions = cachedQueries.stream()
                .filter(element -> !parsedElements.contains(element))
                .collect(Collectors.toList());
        Annotation[] deletions = deletedPositions.stream()
                .map(SQLScriptElementImpl::getAnnotation)
                .toArray(Annotation[]::new);
        model.modifyAnnotations(deletions, additions, null);
        cache.removeAll(deletedPositions);
        cache.addAll(additions.values());
    }

    @Nullable
    private List<SQLScriptElement> extractQueries(int offset, int length) {
        return editor.extractScriptQueries(offset, length, false, true, false);
    }

    private boolean deservesFolding(SQLScriptElement element) {
        int numberOfLines = getNumberOfLines(element);
        if (numberOfLines == 1) {
            return false;
        }
        if (element.getOffset() + element.getLength() != document.getLength() && expandQueryLength(element) == element.getLength()) {
            return numberOfLines > 2;
        }
        return true;
    }

    private int getNumberOfLines(SQLScriptElement element) {
        try {
            return document.getLineOfOffset(element.getOffset() + element.getLength()) - document.getLineOfOffset(element.getOffset()) + 1;
        } catch (BadLocationException e) {
            throw new SQLReconcilingStrategyException(e);
        }
    }

    //expands query to the end of the line if there are only whitespaces after it. Returns desired length.
    private int expandQueryLength(SQLScriptElement element) { //todo simplify
        int position = element.getOffset() + element.getLength();
        while (position < document.getLength()) {
            char c = unsafeGetChar(position);
            if (c == '\n') {
                if (position + 1 < document.getLength()) {
                    position++;
                    break;
                }
            }
            if (Character.isWhitespace(c)) {
                position++;
            } else {
                return element.getLength();
            }
        }
        return position - element.getOffset();
    }

    private char unsafeGetChar(int index) {
        try {
            return document.getChar(index);
        } catch (BadLocationException e) {
            throw new SQLReconcilingStrategyException(e);
        }
    }

    private static class SQLReconcilingStrategyException extends RuntimeException {
        private SQLReconcilingStrategyException(Throwable cause) {
            super(cause);
        }
    }

    private static class SQLScriptElementImpl extends Position implements SQLScriptElement, Comparable<SQLScriptElementImpl> {
        @Nullable
        private ProjectionAnnotation annotation;

        SQLScriptElementImpl(int offset, int length) {
            super(offset, length);
        }

        @Nullable
        public ProjectionAnnotation getAnnotation() {
            return annotation;
        }

        public void setAnnotation(@Nullable ProjectionAnnotation annotation) {
            this.annotation = annotation;
        }

        @Override
        public int compareTo(@NotNull SQLScriptElementImpl o) {
            int diff = getOffset() - o.getOffset();
            if (diff != 0) {
                return diff;
            }
            return getLength() - o.getLength();
        }

        @Override
        public boolean equals(Object o) {
            if (o instanceof Position) {
                Position p = (Position) o;
                return equals(p.getOffset(), p.getLength());
            }
            if (o instanceof SQLScriptElement) {
                SQLScriptElement e = (SQLScriptElement) o;
                return equals(e.getOffset(), e.getLength());
            }
            return false;
        }

        private boolean equals(int offset, int length) {
            return getOffset() == offset && getLength() == length;
        }

        @Override
        public int hashCode() {
            return Objects.hash(getOffset(), getLength());
        }

        @NotNull
        @Override
        public String getOriginalText() {
            return "";
        }

        @NotNull
        @Override
        public String getText() {
            return "";
        }

        @Override
        public Object getData() {
            return "";
        }

        @Override
        public void setData(Object data) {
            //do nothing
        }

        @Override
        public void reset() {
            //do nothing
        }
    }
}
