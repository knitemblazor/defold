#ifndef PHYSICS_DEBUG_DRAW_2D_H
#define PHYSICS_DEBUG_DRAW_2D_H

#include "Box2D/Box2D.h"

#include "physics.h"

namespace dmPhysics
{
    class DebugDraw2D : public b2DebugDraw
    {
    public:
        DebugDraw2D(DebugCallbacks* callbacks);

        /// Draw a closed polygon provided in CCW order.
        virtual void DrawPolygon(const b2Vec2* vertices, int32 vertexCount, const b2Color& color);

        /// Draw a solid closed polygon provided in CCW order.
        virtual void DrawSolidPolygon(const b2Vec2* vertices, int32 vertexCount, const b2Color& color);

        /// Draw a circle.
        virtual void DrawCircle(const b2Vec2& center, float32 radius, const b2Color& color);

        /// Draw a solid circle.
        virtual void DrawSolidCircle(const b2Vec2& center, float32 radius, const b2Vec2& axis, const b2Color& color);

        /// Draw a line segment.
        virtual void DrawSegment(const b2Vec2& p1, const b2Vec2& p2, const b2Color& color);

        /// Draw a transform. Choose your own length scale.
        /// @param xf a transform.
        virtual void DrawTransform(const b2Transform& xf);

    private:
        DebugCallbacks* m_Callbacks;
    };
}

#endif // PHYSICS_DEBUG_DRAW_2D_H
