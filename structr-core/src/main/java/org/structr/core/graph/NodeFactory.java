/*
 *  Copyright (C) 2010-2013 Axel Morgner, structr <structr@structr.org>
 *
 *  This file is part of structr <http://structr.org>.
 *
 *  structr is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  structr is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with structr.  If not, see <http://www.gnu.org/licenses/>.
 */



package org.structr.core.graph;

import org.neo4j.gis.spatial.indexprovider.SpatialRecordHits;
import org.neo4j.graphdb.Direction;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.NotFoundException;
import org.neo4j.graphdb.index.IndexHits;

import org.structr.common.RelType;
import org.structr.common.SecurityContext;
import org.structr.common.error.FrameworkException;
import org.structr.common.error.IdNotFoundToken;
import org.structr.core.Result;
import org.structr.core.Services;
import org.structr.core.entity.*;
import org.structr.core.entity.AbstractNode;

//~--- JDK imports ------------------------------------------------------------

import java.lang.reflect.Constructor;

import java.util.*;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.structr.core.EntityContext;
import org.structr.core.module.ModuleService;

//~--- classes ----------------------------------------------------------------

/**
 * A factory for structr nodes.
 *
 * @author Christian Morgner
 * @author Axel Morgner
 */
public class NodeFactory<T extends AbstractNode> {

	public static final int DEFAULT_PAGE      = 1;
	public static final int DEFAULT_PAGE_SIZE = Integer.MAX_VALUE;
	private static final Logger logger        = Logger.getLogger(NodeFactory.class.getName());

	//~--- fields ---------------------------------------------------------

	private Map<Class, Constructor<T>> constructors = new LinkedHashMap<Class, Constructor<T>>();

	// encapsulates all criteria for node creation
	private FactoryProfile factoryProfile;

	//~--- constructors ---------------------------------------------------

	public NodeFactory() {}

	public NodeFactory(final SecurityContext securityContext) {

		factoryProfile = new FactoryProfile(securityContext);

	}

	public NodeFactory(final SecurityContext securityContext, final boolean includeDeletedAndHidden, final boolean publicOnly) {

		factoryProfile = new FactoryProfile(securityContext, includeDeletedAndHidden, publicOnly);

	}

	public NodeFactory(final SecurityContext securityContext, final int pageSize, final int page, final String offsetId) {

		factoryProfile = new FactoryProfile(securityContext);

		factoryProfile.setPageSize(pageSize);
		factoryProfile.setPage(page);
		factoryProfile.setOffsetId(offsetId);

	}

	public NodeFactory(final SecurityContext securityContext, final boolean includeDeletedAndHidden, final boolean publicOnly, final int pageSize, final int page, final String offsetId) {

		factoryProfile = new FactoryProfile(securityContext, includeDeletedAndHidden, publicOnly, pageSize, page, offsetId);

	}

	//~--- methods --------------------------------------------------------

	public T createNode(final Node node) {

		String type     = AbstractNode.type.dbName();
		String nodeType = node.hasProperty(type)
				  ? (String) node.getProperty(type)
				  : "GenericNode";

		return createNodeWithType(node, nodeType);

	}

	public T createNodeWithType(final Node node, final String nodeType) {

		SecurityContext securityContext = factoryProfile.getSecurityContext();
		T newNode = (T)securityContext.lookup(node);
		
		if (newNode == null) {

			Class<T> nodeClass = Services.getService(ModuleService.class).getNodeEntityClass(nodeType);
			if (nodeClass != null) {

				try {

					Constructor<T> constructor = constructors.get(nodeClass);
					if (constructor == null) {

						constructor = nodeClass.getConstructor();

						constructors.put(nodeClass, constructor);

					}

					// newNode = (AbstractNode) nodeClass.newInstance();
					newNode = constructor.newInstance();

				} catch (Throwable t) {

					newNode = null;

				}

			}

			if (newNode == null) {
				// FIXME
				newNode = (T)EntityContext.getGenericFactory().createGenericNode();
			}


			newNode.init(factoryProfile.getSecurityContext(), node);
			newNode.onNodeInstantiation();

			String newNodeType = newNode.getProperty(AbstractNode.type);
			if (newNodeType == null || (newNodeType != null && !newNodeType.equals(nodeType))) {
				
				try {

					newNode.setType(nodeType);

				} catch (Throwable t) {

					logger.log(Level.SEVERE, "Unable to set type property {0} on node {1}: {2}", new Object[] { nodeType, newNode, t.getMessage() } );
				}
			}
			
			// cache node for this request
			securityContext.store(newNode);
		}
		
		// check access
		if (securityContext.isReadable(newNode, factoryProfile.includeDeletedAndHidden(), factoryProfile.publicOnly())) {

			return newNode;
		}
		
		return null;
	}

	public T createNode(final Node node, final boolean includeDeletedAndHidden, final boolean publicOnly) throws FrameworkException {

		factoryProfile.setIncludeDeletedAndHidden(includeDeletedAndHidden);
		factoryProfile.setPublicOnly(publicOnly);

		return createNode(node);

	}

	/**
	 * Create structr nodes from the underlying database nodes
	 *
	 * Include only nodes which are readable in the given security context.
	 * If includeDeletedAndHidden is true, include nodes with 'deleted' flag
	 * If publicOnly is true, filter by 'visibleToPublicUsers' flag
	 *
	 * @param input
	 * @return
	 */
	public Result createNodes(final IndexHits<Node> input) throws FrameworkException {

		if (input != null) {

			if (input instanceof SpatialRecordHits) {

				return resultFromSpatialRecords((SpatialRecordHits) input);
			} else {

				if (factoryProfile.getOffsetId() != null) {

					return resultWithOffsetId(input);
				} else {

					return resultWithoutOffsetId(input);
				}

			}

		}

		return Result.EMPTY_RESULT;

	}

	/**
	 * Create structr nodes from all given underlying database nodes
	 * No paging, but security check
	 *
	 * @param securityContext
	 * @param input
	 * @return
	 */
	public Result createAllNodes(final Iterable<Node> input) throws FrameworkException {

		List<AbstractNode> nodes = new LinkedList<AbstractNode>();

		if ((input != null) && input.iterator().hasNext()) {

			for (Node node : input) {

				AbstractNode n = createNode(node);

				if (n != null) {

					nodes.add(n);
				}

			}

		}

		return new Result(nodes, nodes.size(), true, false);

	}
	/**
	 * Create structr nodes from all given underlying database nodes
	 * No paging, but security check
	 *
	 * @param securityContext
	 * @param input
	 * @return
	 */
	public List<AbstractNode> bulkCreateNodes(final Iterable<Node> input) throws FrameworkException {

		List<AbstractNode> nodes = new LinkedList<AbstractNode>();

		if ((input != null) && input.iterator().hasNext()) {

			for (Node node : input) {

				AbstractNode n = createNode(node);

				if (n != null) {

					nodes.add(n);
				}

			}

		}

		return nodes;

	}

	/**
	 * Create a dummy node (useful when you need an instance
	 * of an {@see AbstractNode} for a db node which was deleted
	 * in current transaction
	 *
	 * @param nodeType
	 * @return
	 * @throws FrameworkException
	 */
	public T createDummyNode(final String nodeType) throws FrameworkException {

		Class<T> nodeClass = Services.getService(ModuleService.class).getNodeEntityClass(nodeType);
		T newNode          = null;

		if (nodeClass != null) {

			try {

				Constructor<T> constructor = constructors.get(nodeClass);

				if (constructor == null) {

					constructor = nodeClass.getConstructor();

					constructors.put(nodeClass, constructor);

				}

				// newNode = (AbstractNode) nodeClass.newInstance();
				newNode = constructor.newInstance();

			} catch (Throwable t) {

				newNode = null;

			}

		}

		return newNode;

	}

	// <editor-fold defaultstate="collapsed" desc="private methods">
	private List<Node> read(final Iterable<Node> it) {

		List<Node> nodes = new LinkedList();

		while (it.iterator().hasNext()) {

			nodes.add(it.iterator().next());
		}

		return nodes;

	}

	private Result resultWithOffsetId(final IndexHits<Node> input) throws FrameworkException {

		int size                 = input.size();
		final int pageSize       = Math.min(size, factoryProfile.getPageSize());
		final int page           = factoryProfile.getPage();
		final String offsetId    = factoryProfile.getOffsetId();
		List<AbstractNode> nodes = new LinkedList<AbstractNode>();
		int position             = 0;
		int count                = 0;
		int offset               = 0;

		// We have an offsetId, so first we need to
		// find the node with this uuid to get the offset
		List<AbstractNode> nodesUpToOffset = new LinkedList();
		int i                       = 0;
		boolean gotOffset           = false;

		for (Node node : input) {

			AbstractNode n = createNode(node);

			nodesUpToOffset.add(n);

			if (!gotOffset) {

				if (!offsetId.equals(n.getUuid())) {

					i++;

					continue;

				}

				gotOffset = true;
				offset    = page > 0
					    ? i
					    : i + (page * pageSize);

				break;

			}

		}

		if (!gotOffset) {

			throw new FrameworkException("offsetId", new IdNotFoundToken(offsetId));
		}
		
		if (offset < 0) {
			
			// Remove last item
			nodesUpToOffset.remove(nodesUpToOffset.size()-1);
			
			return new Result(nodesUpToOffset, size, true, false);
		}

		for (AbstractNode node : nodesUpToOffset) {

			if (node != null) {

				if (++position > offset) {

					// stop if we got enough nodes
					if (++count > pageSize) {

						return new Result(nodes, size, true, false);
					}

					nodes.add(node);
				}

			}

		}

		// If we get here, the result was not complete, so we need to iterate
		// through the index result (input) to get more items.
		for (Node node : input) {

			AbstractNode n = createNode(node);

			if (n != null) {

				if (++position > offset) {

					// stop if we got enough nodes
					if (++count > pageSize) {

						return new Result(nodes, size, true, false);
					}

					nodes.add(n);
				}

			}

		}

		return new Result(nodes, size, true, false);

	}

	private Result resultWithoutOffsetId(final IndexHits<Node> input) throws FrameworkException {

		final int pageSize = factoryProfile.getPageSize();
		final int page     = factoryProfile.getPage();
		int fromIndex;

		if (page < 0) {

			List<Node> rawNodes = read(input);
			int size            = rawNodes.size();

			fromIndex = Math.max(0, size + (page * pageSize));

			final List<AbstractNode> nodes = new LinkedList<AbstractNode>();
			int toIndex                    = Math.min(size, fromIndex + pageSize);

			for (Node n : rawNodes.subList(fromIndex, toIndex)) {

				nodes.add(createNode(n));
			}

			// We've run completely through the iterator,
			// so the overall count from here is accurate.
			return new Result(nodes, size, true, false);

		} else {

			// FIXME: IndexHits#size() may be inaccurate!
			int size = input.size();

			fromIndex = pageSize == Integer.MAX_VALUE ? 0 : (page - 1) * pageSize;
			//fromIndex = (page - 1) * pageSize;

			// The overall count may be inaccurate
			return page(input, size, fromIndex, pageSize);
		}

	}

	private Result page(final IndexHits<Node> input, final int overallResultCount, final int offset, final int pageSize) throws FrameworkException {

		final List<AbstractNode> nodes = new LinkedList<AbstractNode>();
		int position                   = 0;
		int count                      = 0;
		int overallCount               = 0;

		for (Node node : input) {

			AbstractNode n = createNode(node);

			overallCount++;

			if (n != null) {

				if (++position > offset) {

					// stop if we got enough nodes
					if (++count > pageSize) {

						// The overall count may be inaccurate
						return new Result(nodes, overallResultCount, true, false);
					}

					nodes.add(n);
				}

			}

		}

		// We've run completely through the iterator,
		// so the overall count from here is accurate.
		return new Result(nodes, overallCount, true, false);

	}

	private Result resultFromSpatialRecords(final SpatialRecordHits spatialRecordHits) throws FrameworkException {

		final int pageSize                    = factoryProfile.getPageSize();
		final int page                        = factoryProfile.getPage();
		final SecurityContext securityContext = factoryProfile.getSecurityContext();
		final boolean includeDeletedAndHidden = factoryProfile.includeDeletedAndHidden();
		final boolean publicOnly              = factoryProfile.publicOnly();
		List<AbstractNode> nodes              = new LinkedList<AbstractNode>();
		int position                          = 0;
		int count                             = 0;
		int offset                            = 0;
		int size                              = spatialRecordHits.size();
		GraphDatabaseCommand graphDbCommand   = Services.command(securityContext, GraphDatabaseCommand.class);
		GraphDatabaseService graphDb          = graphDbCommand.execute();

		for (Node node : spatialRecordHits) {

			Long dbNodeId = null;
			Node realNode = node; //null;

//			if (node.hasProperty("id")) {
//
//				dbNodeId = (Long) node.getProperty("id");
//
//				try {
//
//					realNode = graphDb.getNodeById(dbNodeId);
//
//				} catch (NotFoundException nfe) {
//
//					// Should not happen, but it does
//					// FIXME: Why does the spatial index return an unknown ID?
//					logger.log(Level.SEVERE, "Node with id {0} not found.", dbNodeId);
//
//					for (String key : node.getPropertyKeys()) {
//
//						logger.log(Level.FINE, "{0}={1}", new Object[] { key, node.getProperty(key) });
//					}
//				}
//
//			}

			if (realNode != null) {

				AbstractNode n = createNode(realNode);
				
				nodes.add(n);

				// Check is done in createNodeWithType already, so we don't have to do it again
				if (n != null) {    // && isReadable(securityContext, n, includeDeletedAndHidden, publicOnly)) {

					List<AbstractNode> nodesAt = getNodesAt(n);

					size += nodesAt.size();

					for (AbstractNode nodeAt : nodesAt) {

						if (nodeAt != null && securityContext.isReadable(nodeAt, includeDeletedAndHidden, publicOnly)) {

							if (++position > offset) {

								// stop if we got enough nodes
								if (++count > pageSize) {

									return new Result(nodes, size, true, false);
								}

								nodes.add(nodeAt);
							}

						}

					}

				}

			}

		}

		return new Result(nodes, size, true, false);

	}

	//~--- get methods ----------------------------------------------------

	/**
	 * Return all nodes which are connected by an incoming IS_AT relationship
	 *
	 * @param locationNode
	 * @return
	 */
	private List<AbstractNode> getNodesAt(final AbstractNode locationNode) {

		List<AbstractNode> nodes = new LinkedList<AbstractNode>();

		for (AbstractRelationship rel : locationNode.getRelationships(RelType.IS_AT, Direction.INCOMING)) {

			nodes.add(rel.getStartNode());
		}

		return nodes;

	}

	//~--- inner classes --------------------------------------------------

	private class FactoryProfile {

		private boolean includeDeletedAndHidden = true;
		private String offsetId                 = null;
		private boolean publicOnly              = false;
		private int pageSize                    = DEFAULT_PAGE_SIZE;
		private int page                        = DEFAULT_PAGE;
		private SecurityContext securityContext = null;

		//~--- constructors -------------------------------------------

		public FactoryProfile(final SecurityContext securityContext) {

			this.securityContext = securityContext;

		}

		public FactoryProfile(final SecurityContext securityContext, final boolean includeDeletedAndHidden, final boolean publicOnly) {

			this.securityContext         = securityContext;
			this.includeDeletedAndHidden = includeDeletedAndHidden;
			this.publicOnly              = publicOnly;

		}

		public FactoryProfile(final SecurityContext securityContext, final boolean includeDeletedAndHidden, final boolean publicOnly, final int pageSize, final int page,
				      final String offsetId) {

			this.securityContext         = securityContext;
			this.includeDeletedAndHidden = includeDeletedAndHidden;
			this.publicOnly              = publicOnly;
			this.pageSize                = pageSize;
			this.page                    = page;
			this.offsetId                = offsetId;

		}

		//~--- methods ------------------------------------------------

		/**
		 * @return the includeDeletedAndHidden
		 */
		public boolean includeDeletedAndHidden() {

			return includeDeletedAndHidden;

		}

		/**
		 * @return the publicOnly
		 */
		public boolean publicOnly() {

			return publicOnly;

		}

		//~--- get methods --------------------------------------------

		/**
		 * @return the offsetId
		 */
		public String getOffsetId() {

			return offsetId;

		}

		/**
		 * @return the pageSize
		 */
		public int getPageSize() {

			return pageSize;

		}

		/**
		 * @return the page
		 */
		public int getPage() {

			return page;

		}

		/**
		 * @return the securityContext
		 */
		public SecurityContext getSecurityContext() {

			return securityContext;

		}

		//~--- set methods --------------------------------------------

		/**
		 * @param includeDeletedAndHidden the includeDeletedAndHidden to set
		 */
		public void setIncludeDeletedAndHidden(boolean includeDeletedAndHidden) {

			this.includeDeletedAndHidden = includeDeletedAndHidden;

		}

		/**
		 * @param offsetId the offsetId to set
		 */
		public void setOffsetId(String offsetId) {

			this.offsetId = offsetId;

		}

		/**
		 * @param publicOnly the publicOnly to set
		 */
		public void setPublicOnly(boolean publicOnly) {

			this.publicOnly = publicOnly;

		}

		/**
		 * @param pageSize the pageSize to set
		 */
		public void setPageSize(int pageSize) {

			this.pageSize = pageSize;

		}

		/**
		 * @param page the page to set
		 */
		public void setPage(int page) {

			this.page = page;

		}

		/**
		 * @param securityContext the securityContext to set
		 */
		public void setSecurityContext(SecurityContext securityContext) {

			this.securityContext = securityContext;

		}

	}

	// </editor-fold>

}
