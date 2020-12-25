/*
 * Copyright (c) 2009-2020 Weasis Team and other contributors.
 *
 * This program and the accompanying materials are made available under the terms of the Eclipse
 * Public License 2.0 which is available at http://www.eclipse.org/legal/epl-2.0.
 *
 * SPDX-License-Identifier: EPL-2.0
 */

package org.weasis.core.ui.model.graphic;

import java.awt.geom.AffineTransform;
import java.awt.geom.Area;
import java.util.List;

import org.weasis.core.api.image.util.MeasurableLayer;
import org.weasis.core.ui.editor.image.ImageRegionStatistics;
import org.weasis.core.ui.model.utils.bean.MeasureItem;

public abstract class AbstractDragGraphicArea extends AbstractDragGraphic implements GraphicArea {
    private static final long serialVersionUID = -3042328664891626708L;

    public AbstractDragGraphicArea(Integer pointNumber) {
        super(pointNumber);
    }

    public AbstractDragGraphicArea(AbstractDragGraphicArea graphic) {
        super(graphic);
    }

    @Override
    public Area getArea(AffineTransform transform) {
        if (shape == null) {
            return new Area();
        } else {
            Area area = super.getArea(transform);
            area.add(new Area(shape)); // Add inside area for closed shape
            return area;
        }
    }

    @Override
    public List<MeasureItem> getImageStatistics(MeasurableLayer layer, Boolean releaseEvent) {
        return ImageRegionStatistics.getImageStatistics(this, layer, releaseEvent);
    }
}
