package com.github.bohnman.squiggly.core.match;

import com.github.bohnman.core.cache.CoreCache;
import com.github.bohnman.core.cache.CoreCacheBuilder;
import com.github.bohnman.core.json.path.CoreJsonPath;
import com.github.bohnman.core.json.path.CoreJsonPathElement;
import com.github.bohnman.core.tuple.CorePair;
import com.github.bohnman.squiggly.core.BaseSquiggly;
import com.github.bohnman.squiggly.core.bean.BeanInfo;
import com.github.bohnman.squiggly.core.context.SquigglyContext;
import com.github.bohnman.squiggly.core.metric.source.CoreCacheSquigglyMetricsSource;
import com.github.bohnman.squiggly.core.name.AnyDeepName;
import com.github.bohnman.squiggly.core.name.ExactName;
import com.github.bohnman.squiggly.core.parser.ParseContext;
import com.github.bohnman.squiggly.core.parser.node.SquigglyNode;
import com.github.bohnman.squiggly.core.view.PropertyView;

import java.util.*;

import static com.github.bohnman.core.lang.CoreAssert.notNull;

/**
 * Encapsulates the filter node matching logic.
 */
public class SquigglyNodeMatcher {

    /**
     * Indicate to never match the path.
     */
    public static final SquigglyNode NEVER_MATCH = new SquigglyNode(new ParseContext(1, 1), AnyDeepName.get(), Collections.emptyList(), Collections.emptyList(), Collections.emptyList(), false, false, false);

    /**
     * Indicate to always match the path.
     */
    public static final SquigglyNode ALWAYS_MATCH = new SquigglyNode(new ParseContext(1, 1), AnyDeepName.get(), Collections.emptyList(), Collections.emptyList(), Collections.emptyList(), false, false, false);

    private static final List<SquigglyNode> BASE_VIEW_NODES = Collections.singletonList(new SquigglyNode(new ParseContext(1, 1), new ExactName(PropertyView.BASE_VIEW), Collections.emptyList(), Collections.emptyList(), Collections.emptyList(), false, true, false));

    private final CoreCache<CorePair<CoreJsonPath, String>, SquigglyNode> matchCache;
    private final BaseSquiggly squiggly;


    /**
     * Constructor.
     *
     * @param squiggly configurator
     */
    @SuppressWarnings("unchecked")
    public SquigglyNodeMatcher(BaseSquiggly squiggly) {
        this.squiggly = notNull(squiggly);
        this.matchCache = CoreCacheBuilder.from(squiggly.getConfig().getFilterPathCacheSpec()).build();
        squiggly.getMetrics().add(new CoreCacheSquigglyMetricsSource("squiggly.filter.pathCache.", matchCache));
    }

    /**
     * Perform the matching using a context.
     *
     * @param path    the path that is being matched
     * @param context the context holding the root node
     * @return matched node or {@link #ALWAYS_MATCH} or {@link #NEVER_MATCH}
     */
    @SuppressWarnings("unchecked")
    public SquigglyNode match(CoreJsonPath path, SquigglyContext context) {
        return match(path, context.getFilter(), context.getNode());
    }

    /**
     * Perform the matching using the given node.
     *
     * @param path   that thst is beign matched
     * @param filter the filter string
     * @param node   the root node
     * @return matched node or {@link #ALWAYS_MATCH} or {@link #NEVER_MATCH}
     */
    public SquigglyNode match(CoreJsonPath path, String filter, SquigglyNode node) {
        if (AnyDeepName.ID.equals(filter)) {
            return ALWAYS_MATCH;
        }

        if (path.isCachable()) {
            // cache the match result using the path and filter expression
            CorePair<CoreJsonPath, String> pair = CorePair.of(path, filter);
            SquigglyNode match = matchCache.get(pair);

            if (match == null) {
                match = matchInternal(path, node);
            }

            matchCache.put(pair, match);
            return match;
        }

        return matchInternal(path, node);
    }

    private SquigglyNode matchInternal(CoreJsonPath path, SquigglyNode node) {
        List<SquigglyNode> nodes = node.getChildren();
        Set<String> viewStack = null;
        SquigglyNode viewNode = null;
        SquigglyNode match = null;

        int pathSize = path.getElements().size();
        int lastIdx = pathSize - 1;

        for (int i = 0; i < pathSize; i++) {
            CoreJsonPathElement element = path.getElements().get(i);

            if (viewNode != null && !viewNode.isSquiggly()) {
                Class beanClass = element.getBeanClass();

                if (beanClass != null && !Map.class.isAssignableFrom(beanClass)) {
                    Set<String> propertyNames = getPropertyNamesFromViewStack(element, viewStack);

                    if (!propertyNames.contains(element.getName())) {
                        return NEVER_MATCH;
                    }
                }

            } else if (nodes.isEmpty()) {
                return NEVER_MATCH;
            } else {
                match = findBestSimpleNode(element, nodes);

                if (match == null) {
                    match = findBestViewNode(element, nodes);

                    if (match != null) {
                        viewNode = match;
                        viewStack = addToViewStack(viewStack, viewNode);
                    }
                } else if (match.isAnyShallow()) {
                    viewNode = match;
                } else if (match.isAnyDeep()) {
                    return match;
                }

                if (match == null) {
                    if (isJsonUnwrapped(element)) {
                        match = ALWAYS_MATCH;
                        continue;
                    }

                    return NEVER_MATCH;
                }

                if (match.isNegated()) {
                    return NEVER_MATCH;
                }

                nodes = match.getChildren();

                if (i < lastIdx && nodes.isEmpty() && !match.isEmptyNested() && squiggly.getConfig().isFilterImplicitlyIncludeBaseFields()) {
                    nodes = BASE_VIEW_NODES;
                }
            }
        }

        if (match == null) {
            match = NEVER_MATCH;
        }

        return match;
    }


    private boolean isJsonUnwrapped(CoreJsonPathElement element) {
        BeanInfo info = squiggly.getBeanInfoIntrospector().introspect(element.getBeanClass());
        return info.isUnwrapped(element.getName());
    }

    private Set<String> getPropertyNamesFromViewStack(CoreJsonPathElement element, Set<String> viewStack) {
        if (viewStack == null) {
            return getPropertyNames(element, PropertyView.BASE_VIEW);
        }

        Set<String> propertyNames = new HashSet<>();

        for (String viewName : viewStack) {
            Set<String> names = getPropertyNames(element, viewName);

            if (names.isEmpty() && squiggly.getConfig().isFilterImplicitlyIncludeBaseFields()) {
                names = getPropertyNames(element, PropertyView.BASE_VIEW);
            }

            propertyNames.addAll(names);
        }

        return propertyNames;
    }

    private SquigglyNode findBestViewNode(CoreJsonPathElement element, List<SquigglyNode> nodes) {
        if (Map.class.isAssignableFrom(element.getBeanClass())) {
            for (SquigglyNode node : nodes) {
                if (PropertyView.BASE_VIEW.equals(node.getName())) {
                    return node;
                }
            }
        } else {
            for (SquigglyNode node : nodes) {
                // handle view
                Set<String> propertyNames = getPropertyNames(element, node.getName());

                if (propertyNames.contains(element.getName())) {
                    return node;
                }
            }
        }

        return null;
    }

    private SquigglyNode findBestSimpleNode(CoreJsonPathElement element, List<SquigglyNode> nodes) {
        SquigglyNode match = null;
        int lastMatchStrength = -1;

        for (SquigglyNode node : nodes) {
            int matchStrength = node.match(element.getName());

            if (matchStrength < 0) {
                continue;
            }

            if (lastMatchStrength < 0 || matchStrength >= lastMatchStrength) {
                match = node;
                lastMatchStrength = matchStrength;
            }

        }

        return match;
    }

    private Set<String> addToViewStack(Set<String> viewStack, SquigglyNode viewNode) {
        if (!squiggly.getConfig().isFilterPropagateViewToNestedFilters()) {
            return null;
        }

        if (viewStack == null) {
            viewStack = new HashSet<>();
        }

        viewStack.add(viewNode.getName());

        return viewStack;
    }

    private Set<String> getPropertyNames(CoreJsonPathElement element, String viewName) {
        Class beanClass = element.getBeanClass();

        if (beanClass == null) {
            return Collections.emptySet();
        }

        return squiggly.getBeanInfoIntrospector().introspect(beanClass).getPropertyNamesForView(viewName);
    }
}
