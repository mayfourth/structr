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



package org.structr.web.node;

import au.com.bytecode.opencsv.CSVReader;

import org.structr.common.RelType;
import org.structr.common.error.FrameworkException;
import org.structr.core.Services;
import org.structr.core.UnsupportedArgumentError;
import org.structr.core.entity.AbstractNode;
import org.structr.core.entity.NodeList;
import org.structr.core.entity.Principal;
import org.structr.core.graph.CreateNodeCommand;
import org.structr.core.graph.CreateRelationshipCommand;
import org.structr.core.graph.FindNodeCommand;
import org.structr.core.graph.NodeAttribute;
import org.structr.core.graph.NodeServiceCommand;
import org.structr.core.graph.StructrTransaction;
import org.structr.core.graph.TransactionCommand;
import org.structr.web.entity.CsvFile;

//~--- JDK imports ------------------------------------------------------------

import java.io.FileReader;

import java.lang.reflect.Field;

import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.logging.Logger;
import org.structr.core.property.PropertyKey;
import org.structr.core.EntityContext;
import org.structr.core.GraphObject;

//~--- classes ----------------------------------------------------------------

/**
 * Converts a CSV file to a node list. Each row will be represented
 * as a node.
 *
 * @author axel
 */
public class ConvertCsvToNodeListCommand extends NodeServiceCommand {

	private static final Logger logger = Logger.getLogger(ConvertCsvToNodeListCommand.class.getName());

	//~--- methods --------------------------------------------------------

	public Object execute(Object... parameters) throws FrameworkException {

		if (parameters == null || parameters.length < 2) {

			throw new UnsupportedArgumentError("Wrong number of arguments");
		}

		Long csvNodeId          = null;
		AbstractNode sourceNode = null;
		Class targetClass       = null;
		CsvFile csvFileNode     = null;
		String filePath         = null;
		Principal user          = null;

		for (Object o : parameters) {

			if (o instanceof CsvFile) {

				csvFileNode = (CsvFile) o;
				filePath    = Services.getFilesPath() + "/" + csvFileNode.getRelativeFilePath();

			}

			if (o instanceof Long) {

				csvNodeId  = (Long) o;
				sourceNode = (AbstractNode) Services.command(securityContext, FindNodeCommand.class).execute(csvNodeId);

				if (sourceNode instanceof CsvFile) {

					csvFileNode = (CsvFile) sourceNode;
					filePath    = Services.getFilesPath() + "/" + csvFileNode.getRelativeFilePath();

				}

			}

			if (o instanceof Class) {

				targetClass = (Class) o;
			}

			if (o instanceof Principal) {

				user = (Principal) o;
			}

		}

		try {

			// TODO: Implement auto-detection for field separator and quote characters
			CSVReader reader = new CSVReader(new FileReader(filePath), '|', '\"');

			// Read first line, these should be the column keys
			String[] keys = reader.readNext();

			// Get the fields of the target node
			Field[] fields = targetClass.getFields();

			// The field index stores the field name of a column
			Map<Integer, String> fieldIndex = new HashMap<Integer, String>();

			// Instantiate object
			AbstractNode o = (AbstractNode) targetClass.newInstance();
			int col        = 0;

			// Match with fields
			for (String key : keys) {

				for (Field f : fields) {

					String fieldName = (String) f.get(o);

					if (fieldName.toUpperCase().equals(key.toUpperCase())) {

						fieldIndex.put(col, fieldName);
					}

				}

				col++;

			}

			for (Entry<Integer, String> entry : fieldIndex.entrySet()) {

				Integer i = entry.getKey();
				String v  = entry.getValue();

				System.out.println("v: " + v + ", i: " + i);

			}

//                      List<String[]> lines = reader.readAll();
			final Principal userCopy                    = user;
			final AbstractNode sourceNodeCopy           = csvFileNode;
			final TransactionCommand transactionCommand = Services.command(securityContext, TransactionCommand.class);
			final CreateNodeCommand createNode          = Services.command(securityContext, CreateNodeCommand.class);
			final CreateRelationshipCommand createRel   = Services.command(securityContext, CreateRelationshipCommand.class);
			final NodeList<AbstractNode> nodeListNode   = transactionCommand.execute(new StructrTransaction<NodeList<AbstractNode>>() {

				@Override
				public NodeList<AbstractNode> execute() throws FrameworkException {

					// If the node list node doesn't exist, create one
					NodeList<AbstractNode> result = (NodeList) createNode.execute(
										new NodeAttribute(AbstractNode.type, NodeList.class.getSimpleName()),
										new NodeAttribute(AbstractNode.name, sourceNodeCopy.getName() + " List"));

					createRel.execute(sourceNodeCopy, result, RelType.CONTAINS);

					return result;
				}

			});
			final List<List<NodeAttribute>> creationList = new LinkedList<List<NodeAttribute>>();
			String targetClassName                       = targetClass.getSimpleName();
			String[] line                                = null;

			do {

				// read line, one at a time
				try {

					line = reader.readNext();

				} catch (Throwable t) {}

				if (line != null) {

					// create a new list for each item
					List<NodeAttribute> nodeAttributes = new LinkedList<NodeAttribute>();

					nodeAttributes.add(new NodeAttribute(AbstractNode.type, targetClassName));

					for (int i = 0; i < col; i++) {

						String csvValue = line[i];
						String keyName  = fieldIndex.get(i);
						PropertyKey key = EntityContext.getPropertyKeyForDatabaseName(targetClass, keyName);

						nodeAttributes.add(new NodeAttribute(key, csvValue));

					}

					// add node attributes to creation list
					creationList.add(nodeAttributes);
				}
			} while (line != null);

			reader.close();

			// everything in one transaction
			transactionCommand.execute(new StructrTransaction() {

				@Override
				public Object execute() throws FrameworkException {

					List<AbstractNode> nodesToAdd = new LinkedList<AbstractNode>();

					for (List<NodeAttribute> attrList : creationList) {

						nodesToAdd.add(createNode.execute(attrList));    // don't index
					}

					// use bulk add
					nodeListNode.addAll(nodesToAdd);

					return (null);

				}

			});

			/*
			 * final List<NodeAttribute> attrList = new LinkedList<NodeAttribute>();
			 *
			 * NodeAttribute typeAttr = new NodeAttribute(AbstractNode.type.name(), targetClass.getSimpleName());
			 * attrList.add(typeAttr);
			 *
			 * String[] line = null;
			 *
			 * do
			 * {
			 * try
			 * {
			 * line = reader.readNext();
			 *
			 * } catch(Throwable t)
			 * {
			 * line = null;
			 * }
			 *
			 * if(line != null)
			 * {
			 * for(int i = 0; i < col; i++)
			 * {
			 *
			 * String csvValue = line[i];
			 * String key = fieldIndex.get(i);
			 *
			 * System.out.println("Creating attribute " + key + " = '" + csvValue + "'..");
			 *
			 * NodeAttribute attr = new NodeAttribute(key, csvValue);
			 * attrList.add(attr);
			 *
			 * logger.log(Level.FINEST, "Created node attribute {0}={1}", new Object[]
			 * {
			 * attr.getKey(), attr.getValue()
			 * });
			 * }
			 *
			 * AbstractNode newNode = (AbstractNode)transactionCommand.execute(new StructrTransaction()
			 * {
			 * @Override
			 * public Object execute() throws Throwable
			 * {
			 * Command createNode = Services.command(securityContext, CreateNodeCommand.class);
			 *
			 * // Create new node
			 * AbstractNode newNode = (AbstractNode)createNode.execute(userCopy, attrList);
			 * transactionCommand.setExitCode(createNode.getExitCode());
			 * transactionCommand.setErrorMessage(createNode.getErrorMessage());
			 * return newNode;
			 * }
			 * });
			 *
			 * nodeListNode.add(newNode);
			 * logger.log(Level.INFO, "Node {0} added to node list", newNode.getId());
			 * }
			 *
			 * } while(line != null);
			 */
			return nodeListNode;
		} catch (Throwable t) {

			// TODO: use logger
			t.printStackTrace(System.out);
		}

		return null;

	}

}
