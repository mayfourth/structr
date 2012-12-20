/*
 *  Copyright (C) 2010-2012 Axel Morgner, structr <structr@structr.org>
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
package org.structr.core.converter;


//~--- JDK imports ------------------------------------------------------------

import java.util.logging.Logger;
import org.apache.commons.lang.math.NumberUtils;
import org.apache.commons.lang.StringUtils;
import org.structr.common.SecurityContext;
import org.structr.core.Value;

//~--- classes ----------------------------------------------------------------
/**
 *
 * @author Axel Morgner
 */
public class IntConverter extends PropertyConverter {

	private static final Logger logger = Logger.getLogger(IntConverter.class.getName());

	private Value<Integer> value = null;
	
	public IntConverter(SecurityContext securityContext, Value<Integer> value) {
		super(securityContext, null);
		
		this.value = value;
	}
	
	//~--- methods --------------------------------------------------------
	@Override
	public Object convert(Object source) {

		Integer convertedValue = null;
		
		if (source != null) {

			if (source instanceof Integer) {
				convertedValue = (Integer) source;
			} else if (source instanceof Double) {
				convertedValue = (int) Math.round((Double) source);
			} else if (source instanceof Float) {
				convertedValue = (int) Math.round((Float) source);
			} else if (source instanceof String) {
				if (StringUtils.isBlank((String) source)) {
					return null;
				}
				return NumberUtils.createInteger(((String) source));
			}
		}

		return convertedValue;
	}

	@Override
	public Object revert(Object source) {
		
		if (source == null && value != null) {
			return value.get(securityContext);
		}
		
		return source;
	}
}