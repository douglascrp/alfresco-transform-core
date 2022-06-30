/*
 * #%L
 * Alfresco Transform Core
 * %%
 * Copyright (C) 2005 - 2022 Alfresco Software Limited
 * %%
 * This file is part of the Alfresco software.
 * -
 * If the software was purchased under a paid Alfresco license, the terms of
 * the paid license agreement will prevail.  Otherwise, the software is
 * provided under the following open source license terms:
 * -
 * Alfresco is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * -
 * Alfresco is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 * -
 * You should have received a copy of the GNU Lesser General Public License
 * along with Alfresco. If not, see <http://www.gnu.org/licenses/>.
 * #L%
 */
package org.alfresco.transform.base.util;

/**
 * Extends the list of transform options with historic request parameters or 'extra' parameters used in testing
 * or communication in the all-in-one transformer.
 */
public interface RequestParamMap extends org.alfresco.transform.common.RequestParamMap
{
    String TRANSFORM_NAME_PARAMETER = "alfresco.transform-name-parameter";

    String FILE = "file";
    String SOURCE_EXTENSION         = "sourceExtension";
    String SOURCE_MIMETYPE          = "sourceMimetype";
    String TARGET_MIMETYPE          = "targetMimetype";
}
